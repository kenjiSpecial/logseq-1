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
            [lambdaisland.glogi :as log]
            [promesa.core :as p]))

(defonce state
  (atom {:started? false
         :syncing? false
         :last-check-at nil
         :last-error nil
         :last-diff nil
         :last-pull-results nil
         :last-queue-results nil}))

(def ^:private startup-delay-ms 4000)
(def ^:private dirty-queue-delay-ms 3000)
(def ^:private periodic-sync-interval-ms 60000)
(defonce ^:private dirty-queue-timer (atom nil))
(defonce ^:private periodic-sync-timer (atom nil))
(defonce ^:private visibility-listener-registered? (atom false))
(defonce ^:private after-pull-callback (atom nil))

(declare sync-now!)

(defn register-after-pull!
  "Registers a callback invoked after remote files are downloaded. The callback
  receives the pull summary and must return a promise or value."
  [f]
  (reset! after-pull-callback f))

(defn- online?
  []
  (or (nil? (.-onLine js/navigator))
      (true? (.-onLine js/navigator))))

(defn- visible?
  []
  (not= "hidden" (.-visibilityState js/document)))

(defn- trigger-sync!
  [reason]
  (cond
    (not (journal-config/enabled?))
    (p/resolved @state)

    (not (online?))
    (do
      (log/info :journal-mobile/periodic-sync-skipped {:reason reason
                                                       :cause :offline})
      (p/resolved @state))

    (:syncing? @state)
    (do
      (log/info :journal-mobile/periodic-sync-skipped {:reason reason
                                                       :cause :already-syncing})
      (p/resolved @state))

    :else
    (do
      (log/info :journal-mobile/periodic-sync-start {:reason reason})
      (p/catch
       (p/chain (sync-now!)
                (fn [result]
                  (log/info :journal-mobile/periodic-sync-complete {:reason reason
                                                                    :last-error (:last-error @state)})
                  result))
       (fn [error]
         (swap! state assoc :last-error (str error))
         (log/error :journal-mobile/periodic-sync-failed {:reason reason
                                                          :error error})
         nil)))))

(defn- invoke-after-pull!
  [summary]
  (if-let [callback @after-pull-callback]
    (p/catch
     (p/let [result (callback summary)]
       (log/info :journal-mobile/after-pull-callback-complete {:summary (select-keys summary [:downloaded :failed :skippedDirty :attempted])})
       result)
     (fn [error]
       (log/error :journal-mobile/after-pull-callback-failed {:summary summary
                                                              :error error})
       {:error (str error)}))
    (p/resolved nil)))

(defn pull-remote-manifest!
  []
  (api/get-manifest!))

(defn- flush-put!
  [{:keys [path baseRemoteEtag updatedAt] :as item}]
  (p/catch
   (p/let [content (local-graph/read-file-content path)
           _ (api/put-file! path content {:baseRemoteEtag baseRemoteEtag})
           _ (dirty-queue/complete-put! path updatedAt)]
     {:path path
      :status :uploaded})
   (fn [error]
     (p/let [_ (dirty-queue/bump-attempt! path updatedAt error)]
       (log/error :journal-mobile/dirty-queue-flush-failed {:item item
                                                            :error error})
       {:path path
        :status :failed
        :error (str error)}))))

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
  (into {} (map (juxt :path identity) (:entries manifest))))

(defn- changed-entry?
  [local remote]
  (cond
    (and (:etag local) (:etag remote))
    (not= (:etag local) (:etag remote))

    :else
    (or (and (:size local) (:size remote) (not= (:size local) (:size remote)))
        (and (:mtime local) (:mtime remote) (not= (:mtime local) (:mtime remote))))))

(defn- remote-pull-candidates
  [local remote queued-paths]
  (let [local-by-path (entries-by-path (manifest/normalize-manifest local))
        remote-by-path (entries-by-path (manifest/normalize-manifest remote))
        local-paths (set (keys local-by-path))
        remote-paths (set (keys remote-by-path))
        remote-only (set/difference remote-paths local-paths)
        changed (->> (set/intersection local-paths remote-paths)
                     (filter #(changed-entry? (get local-by-path %)
                                              (get remote-by-path %))))
        candidates (concat (map #(vector % :remote-only) remote-only)
                           (map #(vector % :changed) changed))]
    (reduce (fn [result [path reason]]
              (if (contains? queued-paths path)
                (update result :skipped-dirty conj path)
                (update result :downloads conj {:path path
                                                :reason reason})))
            {:downloads []
             :skipped-dirty []}
            (sort-by first candidates))))

(defn- pull-file!
  [{:keys [path reason]}]
  (p/catch
   (p/let [remote-file (api/get-file! path)
           _ (local-graph/write-remote-file! path (:content remote-file))]
     {:path path
      :reason reason
      :status :downloaded
      :etag (:etag remote-file)})
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

(defn- remote-pull!
  ([local remote]
   (remote-pull! local remote #{}))
  ([local remote protected-paths]
   (p/catch
    (p/let [queued-paths (dirty-queue/queued-paths)
            protected-paths (set (concat queued-paths protected-paths))
            {:keys [downloads skipped-dirty]} (remote-pull-candidates local remote protected-paths)
            results (pull-files-sequentially! downloads)
            downloaded-results (filterv #(= :downloaded (:status %)) results)
            downloaded (count downloaded-results)
            failures (filterv #(= :failed (:status %)) results)
            summary {:downloaded downloaded
                     :failed (count failures)
                     :skippedDirty (count skipped-dirty)
                     :attempted (count downloads)
                     :results results
                     :downloadedPaths (mapv :path downloaded-results)
                     :failures failures
                     :skippedDirtyPaths (vec (take 50 skipped-dirty))}
            callback-result (when (pos? downloaded)
                              (invoke-after-pull! summary))
            summary (cond-> summary
                      (:error callback-result)
                      (assoc :callbackError (:error callback-result)))]
      (log/info :journal-mobile/remote-pull-complete summary)
      summary)
    (fn [error]
      (let [summary {:downloaded 0
                     :failed 1
                     :skippedDirty 0
                     :attempted 0
                     :error (str error)}]
        (log/error :journal-mobile/remote-pull-failed error)
        summary)))))

(defn sync-now!
  []
  (if (or (not (journal-config/enabled?))
          (:syncing? @state))
    (p/resolved @state)
    (do
      (swap! state assoc :syncing? true)
      (p/catch
       (p/let [queue-results (flush-dirty-queue!)
               file-objs (local-graph/files)
               local (manifest/local-manifest file-objs)
               remote-result (p/catch (p/chain (pull-remote-manifest!)
                                                (fn [remote]
                                                  {:remote remote}))
                                       (fn [error]
                                         (log/error :journal-mobile/manifest-fetch-failed error)
                                         {:error (str error)}))
               remote (:remote remote-result)
               failed-upload-paths (->> queue-results
                                        (filter #(= :failed (:status %)))
                                        (map :path)
                                        set)
               pull-results (when remote
                              (remote-pull! local remote failed-upload-paths))
               pulled-file-objs (if remote
                                  (local-graph/files)
                                  file-objs)
               pulled-local (manifest/local-manifest pulled-file-objs)
               snapshot (when remote
                          (manifest/save-remote! pulled-local remote))
               checked-at (or (:updatedAt snapshot)
                              (.toISOString (js/Date.)))
               diff (:diff snapshot)
               last-error (or (:error remote-result)
                              (:error pull-results)
                              (:callbackError pull-results))]
         (swap! state assoc
                :syncing? false
                :last-check-at checked-at
                :last-error last-error
                :last-diff diff
                :last-pull-results pull-results
                :last-queue-results queue-results)
         (log/info :journal-mobile/manifest-sync-complete {:diff diff
                                                            :pull-results pull-results
                                                            :queue-results queue-results})
         (assoc (or snapshot {:updatedAt checked-at})
                :pull-results pull-results
                :queue-results queue-results))
       (fn [error]
         (swap! state assoc
                :syncing? false
                :last-error (str error)
                :last-check-at (.toISOString (js/Date.)))
         (log/error :journal-mobile/manifest-sync-failed error)
         nil)))))

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

(defn- start-periodic-sync! []
  (when (and (journal-config/enabled?)
             (nil? @periodic-sync-timer))
    (reset! periodic-sync-timer
            (js/setInterval
             (fn []
               (trigger-sync! :interval))
             periodic-sync-interval-ms))))

(defn- start-visibility-sync! []
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
