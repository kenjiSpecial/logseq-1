(ns frontend.journal-mobile.dirty-queue
  "Persistent dirty-file queue for Journal Android local-first writes."
  (:require ["@capacitor/filesystem" :refer [Directory Encoding Filesystem]]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [logseq.common.path :as path]
            [promesa.core :as p]))

(def ^:private filesystem-directory
  (.-Data Directory))

(def ^:private queue-file-name "dirty-queue.json")
(def ^:private queue-backup-file-name "dirty-queue.json.bak")
(def ^:private queue-temp-file-name "dirty-queue.json.tmp")
(def ^:private allowed-exts #{"md" "markdown" "org" "edn" "css" "js" "excalidraw"})
(def ^:private ignored-paths #{"logseq/graphs-txid.edn"})
(defonce ^:private queue-write-chain (atom (p/resolved nil)))

(defn- filesystem-opts
  [fpath opts]
  (clj->js (merge {:path fpath
                   :directory filesystem-directory}
                  opts)))

(defn- validate-local-graph-dir!
  [dir]
  (let [dir (when (string? dir) (string/trim dir))
        segments (some-> dir (string/split #"/"))]
    (when (or (string/blank? dir)
              (string/starts-with? dir "/")
              (boolean (re-find #"^[A-Za-z]:" dir))
              (string/starts-with? (string/lower-case dir) "file://")
              (string/includes? dir "\\")
              (some #{".."} segments))
      (throw (js/Error. (str "Invalid Journal local graph dir: " (pr-str dir)))))
    (path/path-normalize dir)))

(defn sync-dir
  []
  (path/path-join (validate-local-graph-dir! (journal-config/local-graph-dir))
                  "logseq/.journal-sync"))

(defn queue-path
  []
  (path/path-join (sync-dir) queue-file-name))

(defn- queue-backup-path
  []
  (path/path-join (sync-dir) queue-backup-file-name))

(defn- queue-temp-path
  []
  (path/path-join (sync-dir) queue-temp-file-name))

(defn- blank-queue
  []
  {:items []})

(defn- ensure-sync-dir! []
  (p/catch
   (.mkdir Filesystem (filesystem-opts (sync-dir) {:recursive true}))
   (fn [error]
     (if (string/includes? (str error) "Directory exists")
       nil
       (p/rejected error)))))

(defn- file-exists?
  [fpath]
  (p/catch
   (p/let [stat (.stat Filesystem (filesystem-opts fpath nil))]
     (= "file" (:type (bean/->clj stat))))
   (fn [_] false)))

(defn- parse-queue-data
  [data]
  (let [parsed (js->clj (js/JSON.parse (or data "{}")) :keywordize-keys true)
        items (filterv map? (:items parsed))]
    {:items items}))

(defn- read-queue-file!
  [fpath]
  (p/let [result (.readFile Filesystem (filesystem-opts fpath
                                                        {:encoding (.-UTF8 Encoding)}))
          data (:data (bean/->clj result))]
    (parse-queue-data data)))

(defn- write-queue-data!
  [fpath data]
  (.writeFile Filesystem (filesystem-opts fpath
                                          {:data data
                                           :encoding (.-UTF8 Encoding)
                                           :recursive true})))

(defn- delete-file-if-exists!
  [fpath]
  (p/catch
   (.deleteFile Filesystem (filesystem-opts fpath nil))
   (fn [_] nil)))

(defn- rename-file!
  [from to]
  (.rename Filesystem (clj->js {:from from
                                :to to
                                :directory filesystem-directory
                                :toDirectory filesystem-directory})))

(defn- backup-current-queue!
  []
  (p/let [primary-exists? (file-exists? (queue-path))
          backup-exists? (file-exists? (queue-backup-path))]
    (when (or primary-exists? backup-exists?)
      (p/catch
       (if primary-exists?
         (p/let [result (.readFile Filesystem (filesystem-opts (queue-path)
                                                               {:encoding (.-UTF8 Encoding)}))
                 data (:data (bean/->clj result))
                 _ (parse-queue-data data)]
           (write-queue-data! (queue-backup-path) data))
         (p/rejected (js/Error. "Dirty queue primary file is missing")))
       (fn [primary-error]
         (if backup-exists?
           (p/let [result (.readFile Filesystem (filesystem-opts (queue-backup-path)
                                                                 {:encoding (.-UTF8 Encoding)}))
                   data (:data (bean/->clj result))]
             (parse-queue-data data)
             nil)
           (p/rejected primary-error)))))))

(defn save!
  [queue]
  (if-not (journal-config/enabled?)
    (p/resolved nil)
    (p/catch
     (p/let [_ (ensure-sync-dir!)
             data (js/JSON.stringify (clj->js (or queue (blank-queue))))
             _ (write-queue-data! (queue-temp-path) data)
             _ (backup-current-queue!)
             _ (delete-file-if-exists! (queue-path))
             _ (rename-file! (queue-temp-path) (queue-path))]
       (write-queue-data! (queue-backup-path) data))
     (fn [error]
       (log/error :journal-mobile/dirty-queue-save-failed error)
       (p/rejected error)))))

(defn- update-item
  [queue pred f]
  (update queue :items
          (fn [items]
            (mapv (fn [item]
                    (if (pred item)
                      (f item)
                      item))
                  (or items [])))))

(defn- serialize-queue-write!
  [f]
  (swap! queue-write-chain
         (fn [previous]
           (p/chain
            (p/catch previous
                     (fn [error]
                       (log/error :journal-mobile/dirty-queue-previous-write-failed error)
                       nil))
            (fn [_]
              (f))))))

(defn initialize!
  []
  (if-not (journal-config/enabled?)
    (p/resolved nil)
    (p/catch
     (p/let [_ (ensure-sync-dir!)
             primary-exists? (file-exists? (queue-path))
             backup-exists? (file-exists? (queue-backup-path))]
       (when-not (or primary-exists? backup-exists?)
         (save! (blank-queue))))
     (fn [error]
       (log/error :journal-mobile/dirty-queue-initialize-failed error)
       nil))))

(defn load!
  []
  (if-not (journal-config/enabled?)
    (p/resolved (blank-queue))
    (p/catch
     (p/let [primary-exists? (file-exists? (queue-path))
             backup-exists? (file-exists? (queue-backup-path))]
       (if-not (or primary-exists? backup-exists?)
         (blank-queue)
         (p/catch
          (if primary-exists?
            (read-queue-file! (queue-path))
            (p/rejected (js/Error. "Dirty queue primary file is missing")))
          (fn [primary-error]
            (if backup-exists?
              (p/catch
               (read-queue-file! (queue-backup-path))
               (fn [backup-error]
                 (log/error :journal-mobile/dirty-queue-backup-load-failed backup-error)
                 (p/rejected primary-error)))
              (p/rejected primary-error))))))
     (fn [error]
       (log/error :journal-mobile/dirty-queue-load-failed error)
       (p/rejected error)))))

(defn invalid-relative-path?
  [raw]
  (let [segments (some-> raw (string/split #"/"))]
    (or (string/blank? raw)
        (string/starts-with? raw "/")
        (boolean (re-find #"^[A-Za-z]:" raw))
        (string/starts-with? (string/lower-case raw) "file://")
        (string/includes? raw "\\")
        (some #{".."} segments))))

(defn normalize-relative-path
  [rpath]
  (let [raw (when (string? rpath) (string/trim rpath))]
    (when-not (invalid-relative-path? raw)
      (path/path-normalize raw))))

(defn ignored-path?
  [rpath]
  (let [segments (string/split (or rpath "") #"/")]
    (or (contains? ignored-paths rpath)
        (string/starts-with? rpath "logseq/.journal-sync/")
        (= rpath "logseq/.journal-sync")
        (string/starts-with? rpath "logseq/bak/")
        (= rpath "logseq/bak")
        (string/starts-with? rpath "logseq/version-files/")
        (= rpath "logseq/version-files")
        (some #(string/starts-with? % ".") segments)
        (not (contains? allowed-exts (util/get-file-ext rpath))))))

(defn complete-put!
  ([rpath]
   (complete-put! rpath nil))
  ([rpath updated-at]
   (if-not (journal-config/enabled?)
     (p/resolved nil)
     (if-let [rpath (normalize-relative-path rpath)]
       (serialize-queue-write!
        (fn []
          (p/let [queue (load!)
                  items (filterv #(not (and (= (:path %) rpath)
                                             (= (:op %) "put")
                                             (or (nil? updated-at)
                                                 (= (:updatedAt %) updated-at))))
                                 (:items queue))]
            (save! (assoc queue :items items)))))
       (p/resolved nil)))))

(defn bump-attempt!
  ([rpath error]
   (bump-attempt! rpath nil error))
  ([rpath updated-at error]
   (if-not (journal-config/enabled?)
     (p/resolved nil)
     (if-let [rpath (normalize-relative-path rpath)]
       (serialize-queue-write!
        (fn []
          (p/let [queue (load!)
                  error-message (str error)
                  queue (update-item queue
                                     #(and (= (:path %) rpath)
                                           (= (:op %) "put")
                                           (or (nil? updated-at)
                                               (= (:updatedAt %) updated-at)))
                                     (fn [item]
                                       (assoc item
                                              :attempts (inc (or (:attempts item) 0))
                                              :lastError error-message
                                              :lastAttemptAt (.toISOString (js/Date.)))))]
            (save! queue))))
       (p/resolved nil)))))

(defn- coalesce-put
  [items rpath item]
  (let [existing (first (filter #(and (= (:path %) rpath)
                                      (= (:op %) "put"))
                                items))
        replacement (cond-> (merge {:attempts 0} existing item)
                      (and existing
                           (contains? existing :baseRemoteEtag)
                           (not (contains? item :baseRemoteEtag)))
                      (assoc :baseRemoteEtag (:baseRemoteEtag existing)))]
    (conj (filterv #(not (and (= (:path %) rpath)
                              (= (:op %) "put")))
                   items)
          replacement)))

(defn enqueue-put!
  ([rpath]
   (enqueue-put! rpath nil))
  ([rpath {:keys [baseRemoteEtag]}]
   (if-not (journal-config/enabled?)
     (p/resolved nil)
     (let [enqueue (if-let [rpath (normalize-relative-path rpath)]
                     (if (ignored-path? rpath)
                       (p/resolved nil)
                       (serialize-queue-write!
                        (fn []
                          (p/let [queue (load!)
                                  item (cond-> {:path rpath
                                                :op "put"
                                                :updatedAt (.toISOString (js/Date.))}
                                         baseRemoteEtag (assoc :baseRemoteEtag baseRemoteEtag))
                                  queue (update queue :items #(coalesce-put (vec (or % [])) rpath item))]
                            (save! queue)))))
                     (do
                       (log/warn :journal-mobile/dirty-queue-invalid-path {:path rpath})
                       (p/resolved nil)))]
       (p/catch
        enqueue
        (fn [error]
          (log/error :journal-mobile/dirty-queue-enqueue-failed error)
          (p/rejected error)))))))

(defn items
  []
  (p/chain (load!) :items))

(defn pending-count
  []
  (p/chain (items) count))
