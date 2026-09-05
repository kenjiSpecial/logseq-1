(ns frontend.journal-mobile.sync
  "Lightweight startup sync foundation for Journal Android local-first mode.
  This phase persists the remote manifest, records a diff summary, and flushes
  queued local puts when the network is available."
  (:require [clojure.set :as set]
            [frontend.journal-mobile.api :as api]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.journal-mobile.dirty-queue :as dirty-queue]
            [frontend.journal-mobile.local-graph :as local-graph]
            [frontend.journal-mobile.manifest :as manifest]
            [frontend.journal-mobile.policy :as policy]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

(defonce state
  (atom {:started? false
         :syncing? false
         :phase :idle
         :last-reason nil
         :last-check-at nil
         :last-error nil
         :last-diff nil
         :conflicts []
         :last-queue-results nil
         :queued-count 0}))

(def ^:private startup-delay-ms (:delay-ms (policy/trigger-config :startup)))
(def ^:private dirty-queue-delay-ms (:delay-ms (policy/trigger-config :dirty-queue)))
(def ^:private periodic-sync-interval-ms (:interval-ms (policy/trigger-config :periodic)))
(defonce ^:private dirty-queue-timer (atom nil))
(defonce ^:private periodic-sync-timer (atom nil))
(defonce ^:private visibility-listener-registered? (atom false))
(defonce ^:private after-pull-callback (atom nil))

(defn register-after-pull!
  "Registers a callback invoked after remote files are downloaded."
  [f]
  (reset! after-pull-callback f))

(defn- online?
  []
  (or (not (exists? js/navigator))
      (nil? (.-onLine js/navigator))
      (true? (.-onLine js/navigator))))

(defn- visible?
  []
  (policy/foreground-visible? (.-visibilityState js/document)))

(declare set-phase! sync-now!)

(defn- trigger-sync!
  [reason]
  (cond
    (not (journal-config/enabled?))
    (p/resolved @state)

    (not (online?))
    (do
      (set-phase! :offline reason nil)
      (log/info :journal-mobile/sync-skipped {:reason reason :cause :offline})
      (p/resolved @state))

    (:syncing? @state)
    (do
      (log/info :journal-mobile/sync-skipped {:reason reason :cause :already-syncing})
      (p/resolved @state))

    :else
    (p/catch
     (p/chain (sync-now!)
              (fn [result]
                (swap! state assoc :last-reason reason)
                result))
     (fn [error]
       (set-phase! :error reason (str error))
       (log/error :journal-mobile/sync-failed {:reason reason :error error})
       nil))))

(defn- set-phase!
  [phase reason error]
  (swap! state assoc
         :phase phase
         :last-reason reason
         :last-error error))

(defn pull-remote-manifest!
  []
  (api/get-manifest!))

(defn classify-upload-failure
  "Classifies a failed PUT while retaining the dirty queue item.

  A 412 means that the observed remote revision is stale. Both this conflict
  and other upload failures remain retryable; only the successful path calls
  complete-put!."
  [path status latest-etag error]
  {:path path
   :status (if (= 412 status) :conflict :failed)
   :retryable? true
   :queue-action :retain
   :latestEtag latest-etag
   :error (str error)})

(defn- flush-put!
  [{:keys [path baseRemoteEtag updatedAt] :as item}]
  (p/catch
   (p/let [content (local-graph/read-file-content path)
           result (api/put-file! path content {:baseRemoteEtag baseRemoteEtag})
           _ (manifest/record-remote-etag! path (:etag result))
           _ (dirty-queue/complete-put! path updatedAt)]
     {:path path
      :status :uploaded
      :etag (:etag result)})
   (fn [error]
     (p/let [_ (dirty-queue/bump-attempt! path updatedAt error)]
       (log/error :journal-mobile/dirty-queue-flush-failed {:item item
                                                            :error error})
       (classify-upload-failure path
                                (aget error "status")
                                (aget error "latestEtag")
                                error)))))

(defn- flush-puts-sequentially!
  [puts]
  (p/loop [remaining puts
           results []]
    (if (empty? remaining)
      results
      (p/let [result (flush-put! (first remaining))]
        (p/recur (rest remaining)
                 (conj results result))))))

(defn flush-dirty-queue!
  []
  (if-not (journal-config/enabled?)
    (p/resolved [])
    (p/let [items (dirty-queue/items)
            puts (filterv #(= "put" (:op %)) items)
            results (flush-puts-sequentially! puts)]
      results)))

(defn- entries-by-path
  [manifest]
  (into {} (map (juxt :path identity) (:entries (manifest/normalize-manifest manifest)))))

(defn- changed-entry?
  [local remote]
  (cond
    (and (:etag local) (:etag remote))
    (not= (:etag local) (:etag remote))

    :else
    (or (and (:size local) (:size remote) (not= (:size local) (:size remote)))
        (and (:mtime local) (:mtime remote) (not= (:mtime local) (:mtime remote))))))

(defn pull-candidates
  "Returns remote files that need downloading. Content identities take
   precedence over platform-dependent mtime values. QUEUED-PATHS are always
   protected so a pending local edit cannot be overwritten by a pull."
  [local remote queued-paths]
  (let [local-by-path (entries-by-path local)
        remote-by-path (entries-by-path remote)
        local-paths (set (keys local-by-path))
        remote-paths (set (keys remote-by-path))
        remote-only (set/difference remote-paths local-paths)
        changed (->> (set/intersection local-paths remote-paths)
                     (filter #(changed-entry? (get local-by-path %)
                                              (get remote-by-path %))))
        candidates (concat (map #(vector % :remote-only nil (get remote-by-path %)) remote-only)
                           (map #(vector % :changed (get local-by-path %)
                                         (get remote-by-path %)) changed))]
    (reduce (fn [result [path reason local-entry remote-entry]]
              (if (contains? queued-paths path)
                (update result :skipped-dirty conj path)
                (update result :downloads conj {:path path
                                                :reason reason
                                                :local-entry local-entry
                                                :remote-entry remote-entry})))
            {:downloads []
             :skipped-dirty []}
            (sort-by first candidates))))

(defn- pull-file!
  [{:keys [path reason local-entry]}]
  (p/catch
   (p/let [remote-file (api/get-file! path {:etag (:etag local-entry)})]
     (if (= 304 (:status remote-file))
       {:path path
        :reason reason
        :status :unchanged
        :etag (:etag remote-file)}
       (p/let [_ (local-graph/write-remote-file! path (:content remote-file))]
         {:path path
          :reason reason
          :status :downloaded
          :etag (:etag remote-file)})))
   (fn [error]
     (log/error :journal-mobile/remote-pull-file-failed {:path path
                                                         :reason reason
                                                         :error error})
     {:path path
      :reason reason
      :status :failed
      :error (str error)})))

(defn- pull-files-sequentially!
  [downloads]
  (p/loop [remaining downloads
           results []]
    (if (empty? remaining)
      results
      (p/let [result (pull-file! (first remaining))]
        (p/recur (rest remaining)
                 (conj results result))))))

(defn- invoke-after-pull!
  [summary]
  (if-let [callback @after-pull-callback]
    (p/catch
     (p/let [result (callback summary)]
       (log/info :journal-mobile/after-pull-callback-complete {:downloaded (:downloaded summary)})
       result)
     (fn [error]
       (log/error :journal-mobile/after-pull-callback-failed {:error error})
       {:error (str error)}))
    (p/resolved nil)))

(defn remote-delete-conflicts
  "Classifies remote files deleted since the previous manifest.

  The local copy is deliberately retained. A queued local edit is marked in
  the result as well, so callers can show one explicit conflict instead of
  physically deleting a file that may still contain unsent work."
  [previous-remote remote local queued-paths]
  (let [previous (set (keys (entries-by-path previous-remote)))
        current (set (keys (entries-by-path remote)))
        local-paths (set (keys (entries-by-path local)))
        queued-paths (set queued-paths)]
    (->> (set/intersection (set/difference previous current) local-paths)
         (remove dirty-queue/ignored-path?)
         sort
         (mapv (fn [path]
                 {:path path
                  :type :remote-delete
                  :action :retain-local
                  :queued? (contains? queued-paths path)})))))

(defn- remote-pull!
  [local remote protected-paths previous-remote]
  (p/catch
   (p/let [queued-paths (dirty-queue/queued-paths)
           protected-paths (set (concat queued-paths protected-paths))
           {:keys [downloads skipped-dirty]} (pull-candidates local remote protected-paths)
           results (pull-files-sequentially! downloads)
           downloaded-results (filterv #(= :downloaded (:status %)) results)
           unchanged-results (filterv #(= :unchanged (:status %)) results)
           failures (filterv #(= :failed (:status %)) results)
           deleted (remote-delete-conflicts previous-remote remote local queued-paths)
           summary {:downloaded (count downloaded-results)
                    :unchanged (count unchanged-results)
                    :failed (count failures)
                    :skippedDirty (count skipped-dirty)
                    :attempted (count downloads)
                    :results results
                    :downloadedPaths (mapv :path downloaded-results)
                    :failures failures
                    :skippedDirtyPaths (vec (take 50 skipped-dirty))
                    :remoteDeletedPaths (mapv :path deleted)
                    :conflicts deleted}
           callback-result (when (pos? (:downloaded summary))
                             (invoke-after-pull! summary))]
     (cond-> summary
       (:error callback-result) (assoc :callbackError (:error callback-result))))
   (fn [error]
     (let [summary {:downloaded 0
                    :unchanged 0
                    :failed 1
                    :skippedDirty 0
                    :attempted 0
                    :remoteDeletedPaths []
                    :conflicts []
                    :error (str error)}]
       (log/error :journal-mobile/remote-pull-failed error)
       summary))))

(defn sync-now!
  []
  (if (or (not (journal-config/enabled?))
          (:syncing? @state))
    (p/resolved @state)
    (if-not (online?)
      (do
        (set-phase! :offline :manual nil)
        (p/resolved @state))
      (do
        (swap! state assoc :syncing? true)
        (set-phase! :syncing :manual nil)
      (p/catch
       (p/let [previous-snapshot (manifest/load!)
               queue-results (flush-dirty-queue!)
               queued-count (dirty-queue/pending-count)
               file-objs (local-graph/files)
               local (manifest/local-manifest file-objs)
               remote-result (p/catch
                              (p/let [remote (pull-remote-manifest!)]
                                {:remote remote})
                              (fn [error]
                                (log/error :journal-mobile/manifest-fetch-failed error)
                                {:remote nil
                                 :error (str error)}))
               remote (:remote remote-result)
               protected-paths (->> queue-results
                                    (filter #(contains? #{:failed :conflict} (:status %)))
                                    (map :path)
                                    set)
               pull-results (when remote
                              (remote-pull! local remote protected-paths (:remote previous-snapshot)))
               pulled-file-objs (if remote
                                  (local-graph/files)
                                  file-objs)
               pulled-local (manifest/local-manifest pulled-file-objs)
               snapshot (when remote
                          (manifest/save-remote! pulled-local remote))
               checked-at (or (:updatedAt snapshot)
                              (.toISOString (js/Date.)))
               queue-conflicts (->> queue-results
                                    (filter #(= :conflict (:status %)))
                                    (map #(hash-map :path (:path %)
                                                    :type :upload-conflict
                                                    :latestEtag (:latestEtag %)))
                                    vec)
               pull-conflicts (or (:conflicts pull-results) [])
               conflicts (vec (concat queue-conflicts pull-conflicts))
               pull-error (some :error (:failures pull-results))
               diff (cond-> (:diff snapshot)
                      (seq (:remoteDeletedPaths pull-results))
                      (assoc :remoteDeleted (:remoteDeletedPaths pull-results)))
               queue-error (some :error (filter #(contains? #{:failed :conflict} (:status %)) queue-results))
               last-error (or (:error remote-result)
                              (:error pull-results)
                              (:callbackError pull-results)
                              pull-error
                              queue-error)
               phase (cond
                       (seq conflicts) :conflict
                       last-error :error
                       :else :idle)]
         (swap! state assoc
                :syncing? false
                :phase phase
                :last-reason :manual
                :last-check-at checked-at
                :last-error last-error
                :last-diff diff
                :conflicts conflicts
                :last-queue-results queue-results
                :queued-count queued-count)
         (log/info :journal-mobile/manifest-sync-complete {:diff diff
                                                            :pull-results pull-results
                                                            :queue-results queue-results})
         (assoc (or snapshot {:updatedAt checked-at})
                :pull-results pull-results
                :queue-results queue-results
                :conflicts conflicts))
       (fn [error]
         (swap! state assoc
                :syncing? false
                :phase :error
                :last-reason :manual
                :last-error (str error)
                :last-check-at (.toISOString (js/Date.)))
         (log/error :journal-mobile/manifest-sync-failed error)
         nil))))))

(defn maybe-sync!
  []
  (when (journal-config/enabled?)
    (sync-now!)))

(defn schedule!
  []
  (when (journal-config/enabled?)
    (when-let [timer @dirty-queue-timer]
      (js/clearTimeout timer))
    (reset! dirty-queue-timer
            (js/setTimeout
             (fn []
               (reset! dirty-queue-timer nil)
               (trigger-sync! :dirty-queue))
             dirty-queue-delay-ms))))

(defn- start-periodic-sync!
  []
  (when (and (journal-config/enabled?)
             (nil? @periodic-sync-timer))
    (reset! periodic-sync-timer
            (js/setInterval
             (fn []
               (trigger-sync! :interval))
             periodic-sync-interval-ms))))

(defn- start-visibility-sync!
  []
  (when (and (journal-config/enabled?)
             (not @visibility-listener-registered?))
    (reset! visibility-listener-registered? true)
    (.addEventListener js/document "visibilitychange"
                       (fn []
                         (when (visible?)
                           (trigger-sync! :visibility))))))

(defn stop!
  []
  (when-let [timer @dirty-queue-timer]
    (js/clearTimeout timer)
    (reset! dirty-queue-timer nil))
  (when-let [timer @periodic-sync-timer]
    (js/clearInterval timer)
    (reset! periodic-sync-timer nil))
  (swap! state assoc :started? false)
  @state)

(defn start!
  []
  (when (journal-config/enabled?)
    (when-not (:started? @state)
      (swap! state assoc :started? true)
      (js/setTimeout
       (fn []
         (trigger-sync! :startup))
       startup-delay-ms))
    (start-periodic-sync!)
    (start-visibility-sync!))
  @state)
