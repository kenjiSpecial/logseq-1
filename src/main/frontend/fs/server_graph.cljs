(ns frontend.fs.server-graph
  "Server Graph API based fs implementation for hosted Logseq journal.

   This backend keeps the normal Logseq auto-save pipeline intact: editor changes
   still call frontend.fs/write-file!, and this implementation persists those
   writes immediately to /api/graph/file on the server."
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.fs.protocol :as protocol]
            [frontend.journal-mobile.config :as journal-mobile-config]
            [frontend.util :as util]
            [logseq.common.path :as path]
            [logseq.graph-parser.util :as gp-util]
            [promesa.core :as p]))

(def graph-dir-prefix "server-graph://")
(def default-graph-name "journal")
(def default-dir (str graph-dir-prefix default-graph-name))
(def default-repo (str "logseq_local_" default-dir))

(defn server-graph-dir?
  [dir]
  (and (string? dir)
       (string/starts-with? dir graph-dir-prefix)))

(defn enabled?
  []
  (let [location (.-location js/window)
        params (js/URLSearchParams. (.-search location))
        host (.-hostname location)]
    (and (not (journal-mobile-config/enabled?))
         (or (= "1" (.get params "server-graph"))
             (= "true" (.get params "server-graph"))
             (= "1" (js/localStorage.getItem "logseq.serverGraph"))
             (= "journal.pa-to-po.dev" host)))))

(defn repo-entry
  []
  {:url default-repo
   :nfs? true
   :server-graph? true})

(defn normalize-path
  [dir rpath]
  (let [raw (or rpath "")
        prefix (str (or dir default-dir) "/")
        normalized (cond
                     (= raw (or dir default-dir)) ""
                     (string/starts-with? raw prefix) (subs raw (count prefix))
                     (string/starts-with? raw "/") (subs raw 1)
                     :else raw)]
    (gp-util/path-normalize normalized)))

(defn api-url
  ([endpoint rpath]
   (if (string/blank? rpath)
     endpoint
     (str endpoint "?path=" (js/encodeURIComponent rpath))))
  ([endpoint]
   endpoint))

(defn- response-json
  [response]
  (p/let [body (.json response)]
    (js->clj body :keywordize-keys true)))

(defn- assert-ok!
  [response]
  (if (.-ok response)
    response
    (p/then (.text response)
            (fn [body]
              (throw (js/Error. (str "Server Graph API failed: " (.-status response) " " body)))))))

(defn- fetch-json
  [url opts]
  (p/let [response (js/fetch url opts)
          response (assert-ok! response)]
    (response-json response)))

(defn- fetch-text
  [url]
  (p/let [response (js/fetch url)
          response (assert-ok! response)]
    (.text response)))

(defn- readable-stream-like?
  [content]
  (and content
       (fn? (.-getReader content))))

(def asset-content-types
  {"avif" "image/avif"
   "bmp" "image/bmp"
   "gif" "image/gif"
   "jpeg" "image/jpeg"
   "jpg" "image/jpeg"
   "pdf" "application/pdf"
   "png" "image/png"
   "svg" "image/svg+xml"
   "webp" "image/webp"})

(defn- asset-content-type
  [rpath]
  (get asset-content-types (some-> (util/get-file-ext rpath) string/lower-case)))

(defn- readable-stream->blob
  [content content-type]
  (p/let [blob (.blob (js/Response. content))]
    (if content-type
      (js/Blob. #js [blob] #js {:type content-type})
      blob)))

(defn- write-request-opts
  [rpath content]
  (let [stream? (readable-stream-like? content)
        content-type (if stream?
                       (asset-content-type rpath)
                       "text/plain; charset=utf-8")
        opts #js {:method "PUT"
                  :body content}]
    (p/let [body (if stream?
                   (readable-stream->blob content content-type)
                   content)]
      (set! (.-body opts) body)
      (when content-type
        (set! (.-headers opts) #js {"content-type" content-type}))
      opts)))

(def allowed-file-exts #{"md" "markdown" "org" "excalidraw" "edn" "css" "js"})
(def ignored-path-prefixes #{"." ".recycle" "node_modules" "logseq/bak"
                             "logseq/version-files"})
(def ignored-paths #{"logseq/graphs-txid.edn"})

(defn- ignored-path?
  [rpath]
  (let [segments (string/split (or rpath "") #"/")]
    (or (contains? ignored-paths rpath)
        (some #(or (= rpath %)
                   (string/starts-with? rpath (str % "/")))
              ignored-path-prefixes)
        (some #(and (not= % ".")
                    (string/starts-with? % "."))
              segments))))

(defn- allowed-file?
  [{:keys [path type]}]
  (and (= type "file")
       (not (ignored-path? path))
       (contains? allowed-file-exts (util/get-file-ext path))))

(defn- stat->mtime
  [stat]
  (let [mtime (:mtime stat)]
    (if (string? mtime)
      (.getTime (js/Date. mtime))
      mtime)))

(defn- file-entry->db-file
  [{:keys [path size mtime type content] :as entry}]
  {:name (last (string/split path #"/"))
   :path path
   :mtime (stat->mtime entry)
   :size size
   :type type
   :content content})

(defn- list-entries
  [rpath]
  (p/let [result (fetch-json (api-url "/api/graph/files" rpath) nil)]
    (:entries result)))

(defn- read-entry-content
  [entry]
  (p/let [content (fetch-text (api-url "/api/graph/file" (:path entry)))]
    (assoc entry :content content)))

(defn- walk-files
  [rpath]
  (p/let [entries (list-entries rpath)
          children (p/all
                    (map (fn [entry]
                           (if (ignored-path? (:path entry))
                             []
                             (case (:type entry)
                               "directory" (walk-files (:path entry))
                               "file" (if (allowed-file? entry)
                                        (p/let [entry (read-entry-content entry)]
                                          [(file-entry->db-file entry)])
                                        [])
                               [])))
                         entries))]
    (vec (mapcat identity children))))

(defrecord ServerGraphFs []
  protocol/Fs
  (mkdir! [_this dir]
    (let [rpath (normalize-path default-dir dir)]
      (fetch-json "/api/graph/mkdir"
                  #js {:method "POST"
                       :headers #js {"content-type" "application/json"}
                       :body (js/JSON.stringify #js {:path rpath})})))

  (mkdir-recur! [this dir]
    (protocol/mkdir! this dir))

  (readdir [_this dir]
    (p/let [files (walk-files (normalize-path dir ""))]
      (map :path files)))

  (unlink! [_this _repo fpath _opts]
    (let [rpath (normalize-path default-dir fpath)]
      (p/let [response (js/fetch (api-url "/api/graph/file" rpath)
                                 #js {:method "DELETE"})]
        (assert-ok! response)
        nil)))

  (rmdir! [_this _dir]
    nil)

  (read-file [_this dir rpath _opts]
    (fetch-text (api-url "/api/graph/file" (normalize-path dir rpath))))

  (write-file! [_this repo dir rpath content _opts]
    (let [normalized (normalize-path dir rpath)]
      (p/let [opts (write-request-opts normalized content)
              result (fetch-json (api-url "/api/graph/file" normalized) opts)
              stat (:stat result)]
        (db/set-file-content! repo normalized content)
        (db/set-file-last-modified-at! repo normalized (stat->mtime stat))
        stat)))

  (rename! [this repo old-path new-path]
    (let [old-rpath (normalize-path default-dir old-path)
          new-rpath (normalize-path default-dir new-path)]
      (p/let [content (protocol/read-file this default-dir old-rpath nil)
              _ (protocol/write-file! this repo default-dir new-rpath content nil)
              _ (protocol/unlink! this repo old-rpath nil)]
        nil)))

  (copy! [this repo old-path new-path]
    (let [old-rpath (normalize-path default-dir old-path)
          new-rpath (normalize-path default-dir new-path)]
      (p/let [content (protocol/read-file this default-dir old-rpath nil)
              _ (protocol/write-file! this repo default-dir new-rpath content nil)]
        nil)))

  (stat [_this fpath]
    (p/let [result (fetch-json (api-url "/api/graph/stat" (normalize-path default-dir fpath)) nil)
            stat (:stat result)]
      {:last-modified-at (stat->mtime stat)
       :mtime (stat->mtime stat)
       :size (:size stat)
       :path (:path stat)
       :type (:type stat)}))

  (open-dir [_this dir]
    (p/let [files (walk-files "")]
      {:path (or dir default-dir)
       :files files}))

  (get-files [_this _dir]
    (p/let [files (walk-files "")]
      {:path default-dir
       :files files}))

  (watch-dir! [_this _dir _options]
    nil)

  (unwatch-dir! [_this _dir]
    nil))
