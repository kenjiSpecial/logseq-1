(ns frontend.journal-mobile.manifest
  "Persists the latest Journal sync manifest snapshot in app-private storage."
  (:require ["@capacitor/filesystem" :refer [Directory Encoding Filesystem]]
            [cljs-bean.core :as bean]
            [clojure.set :as set]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.journal-mobile.dirty-queue :as dirty-queue]
            [logseq.graph-parser.util :as gp-util]
            [promesa.core :as p]
            [lambdaisland.glogi :as log]))

(def ^:private filesystem-directory
  (.-Data Directory))

(def ^:private manifest-file-name "manifest.json")
(defonce ^:private latest-remote-manifest (atom nil))

(defn manifest-path
  []
  (str (dirty-queue/sync-dir) "/" manifest-file-name))

(defn- filesystem-opts
  [fpath opts]
  (clj->js (merge {:path fpath
                   :directory filesystem-directory}
                  opts)))

(defn- ensure-sync-dir! []
  (.mkdir Filesystem (filesystem-opts (dirty-queue/sync-dir) {:recursive true})))

(defn- file-exists?
  [fpath]
  (p/catch
   (p/let [stat (.stat Filesystem (filesystem-opts fpath nil))]
     (= "file" (:type (bean/->clj stat))))
   (fn [_] false)))

(defn load!
  []
  (if-not (journal-config/enabled?)
    (p/resolved nil)
    (p/catch
     (p/let [exists? (file-exists? (manifest-path))]
       (when exists?
         (p/let [result (.readFile Filesystem (filesystem-opts (manifest-path)
                                                               {:encoding (.-UTF8 Encoding)}))
                 data (:data (bean/->clj result))]
           (let [manifest (js->clj (js/JSON.parse (or data "{}")) :keywordize-keys true)]
             (reset! latest-remote-manifest (:remote manifest))
             manifest))))
     (fn [error]
       (log/error :journal-mobile/manifest-load-failed error)
       nil))))

(defn save!
  [manifest]
  (if-not (journal-config/enabled?)
    (p/resolved nil)
    (p/catch
     (p/let [_ (ensure-sync-dir!)
             data (js/JSON.stringify (clj->js manifest))]
       (.writeFile Filesystem (filesystem-opts (manifest-path)
                                               {:data data
                                                :encoding (.-UTF8 Encoding)
                                                :recursive true})))
     (fn [error]
       (log/error :journal-mobile/manifest-save-failed error)
       nil))))

(defn- normalize-entry-path
  [rpath]
  (some-> rpath dirty-queue/normalize-relative-path gp-util/path-normalize))

(defn normalize-entry
  [{:keys [path type size mtime etag] :as entry}]
  (when-let [path (normalize-entry-path path)]
    (when-not (dirty-queue/ignored-path? path)
      (cond-> {:path path
               :type (or type "file")}
        size (assoc :size size)
        mtime (assoc :mtime mtime)
        etag (assoc :etag etag)
        (:last-modified-at entry) (assoc :mtime (:last-modified-at entry))))))

(defn normalize-manifest
  [manifest]
  (let [entries (->> (:entries manifest)
                     (keep normalize-entry)
                     (filter #(= "file" (:type %)))
                     (sort-by :path)
                     vec)]
    (assoc manifest :entries entries)))

(defn local-manifest
  [file-objs]
  (let [remote-by-path (into {} (map (juxt :path identity)
                                     (:entries (normalize-manifest @latest-remote-manifest))))]
    (normalize-manifest
     {:graph (journal-config/local-graph-name)
      :generatedAt (.toISOString (js/Date.))
      :entries (mapv (fn [file]
                       (let [path (:file/path file)
                             remote (get remote-by-path path)]
                         (cond-> {:path path
                                  :type "file"
                                  :size (:file/size file)
                                  :mtime (:file/last-modified-at file)}
                           (:etag remote) (assoc :etag (:etag remote)))))
                     file-objs)})))

(defn remote-etag
  "Returns the last server content identity known for RPATH.
   A missing identity means the file was not present in the last manifest;
   callers must use a create-only conditional write in that case."
  [rpath]
  (let [rpath (dirty-queue/normalize-relative-path rpath)
        remote-by-path (into {} (map (juxt :path identity)
                                     (:entries (normalize-manifest @latest-remote-manifest))))]
    (p/resolved (some-> (get remote-by-path rpath) :etag))))

(defn record-remote-etag!
  "Updates the in-memory identity cache after a successful conditional PUT."
  [rpath etag]
  (when-let [rpath (dirty-queue/normalize-relative-path rpath)]
    (when etag
      (swap! latest-remote-manifest
             (fn [remote]
               (let [remote (or (normalize-manifest remote)
                                {:entries []})
                     entries (into {} (map (juxt :path identity) (:entries remote)))]
                 (assoc remote :entries
                        (-> (assoc entries rpath {:path rpath
                                                  :type "file"
                                                  :etag etag})
                            vals
                            vec)))))))
  (p/resolved nil))

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

(defn diff-summary
  [local remote]
  (let [local-by-path (entries-by-path local)
        remote-by-path (entries-by-path remote)
        local-paths (set (keys local-by-path))
        remote-paths (set (keys remote-by-path))
        remote-only (sort (remove dirty-queue/ignored-path? (set/difference remote-paths local-paths)))
        local-only (sort (remove dirty-queue/ignored-path? (set/difference local-paths remote-paths)))
        shared (set/intersection local-paths remote-paths)
        changed (->> shared
                     (filter #(changed-entry? (get local-by-path %) (get remote-by-path %)))
                     (remove dirty-queue/ignored-path?)
                     sort
                     vec)
        unchanged (- (count shared) (count changed))]
    {:remoteRevision (:revision remote)
     :localRevision (:revision local)
     :remoteOnlyCount (count remote-only)
     :localOnlyCount (count local-only)
     :changedCount (count changed)
     :unchangedCount unchanged
     :remoteOnly (vec (take 50 remote-only))
     :localOnly (vec (take 50 local-only))
     :changed (vec (take 50 changed))}))

(defn save-remote!
  [local remote]
  (let [remote (normalize-manifest remote)
        local (normalize-manifest local)
        diff (diff-summary local remote)
        snapshot {:updatedAt (.toISOString (js/Date.))
                  :local local
                  :remote remote
                  :diff diff}]
    (reset! latest-remote-manifest remote)
    (p/let [_ (save! snapshot)]
      snapshot)))
