(ns frontend.journal-mobile.sync
  "Lightweight startup sync foundation for Journal Android local-first mode.
  This phase persists the remote manifest, records a diff summary, and flushes
  queued local puts when the network is available."
  (:require [frontend.journal-mobile.api :as api]
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
         :last-queue-results nil}))

(def ^:private startup-delay-ms 4000)
(def ^:private dirty-queue-delay-ms 3000)
(defonce ^:private dirty-queue-timer (atom nil))

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
               remote (p/catch (pull-remote-manifest!)
                                (fn [error]
                                  (log/error :journal-mobile/manifest-fetch-failed error)
                                  nil))
               snapshot (when remote
                          (manifest/save-remote! local remote))
               checked-at (or (:updatedAt snapshot)
                              (.toISOString (js/Date.)))
               diff (:diff snapshot)]
         (swap! state assoc
                :syncing? false
                :last-check-at checked-at
                :last-error nil
                :last-diff diff
                :last-queue-results queue-results)
         (log/info :journal-mobile/manifest-sync-complete {:diff diff
                                                            :queue-results queue-results})
         (assoc (or snapshot {:updatedAt checked-at})
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
               (p/catch (sync-now!)
                        (fn [error]
                          (swap! state assoc :last-error (str error))
                          (log/error :journal-mobile/scheduled-sync-failed error)
                          nil)))
             dirty-queue-delay-ms))))

(defn start!
  []
  (when (and (journal-config/enabled?)
             (not (:started? @state)))
    (swap! state assoc :started? true)
    (js/setTimeout
     (fn []
       (p/catch (maybe-sync!)
                (fn [error]
                  (swap! state assoc :last-error (str error))
                  (log/error :journal-mobile/startup-sync-failed error)
                  nil)))
     startup-delay-ms))
  @state)
