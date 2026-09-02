(ns frontend.fs.server-graph
  "Server Graph API based fs implementation for hosted Logseq journal.

   This backend keeps the normal Logseq auto-save pipeline intact: editor changes
   still call frontend.fs/write-file!, and this implementation persists those
   writes immediately to /api/graph/file on the server."
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.fs.protocol :as protocol]
            [frontend.util :as util]
            [logseq.graph-parser.util :as gp-util]
            [promesa.core :as p]))

(def graph-dir-prefix "server-graph://")
(def default-graph-name "journal")
(def default-dir (str graph-dir-prefix default-graph-name))
(def default-repo (str "logseq_local_" default-dir))
(defonce ^:private remote-etags (atom {}))

(defn server-graph-dir?
  [dir]
  (and (string? dir)
       (string/starts-with? dir graph-dir-prefix)))

(defn enabled?
  []
  (let [location (.-location js/window)
        params (js/URLSearchParams. (.-search location))
        host (.-hostname location)]
    (or (= "1" (.get params "server-graph"))
        (= "true" (.get params "server-graph"))
        (= "1" (js/localStorage.getItem "logseq.serverGraph"))
        (= "journal.pa-to-po.dev" host))))

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

(defn- normalize-etag
  [etag]
  (when (string? etag)
    (let [etag (string/trim etag)
          etag (if (string/starts-with? etag "W/")
                 (subs etag 2)
                 etag)]
      (if (and (>= (count etag) 2)
               (= \" (first etag))
               (= \" (last etag)))
        (subs etag 1 (dec (count etag)))
        etag))))

(defn- remember-etag!
  [rpath etag]
  (when rpath
    (swap! remote-etags assoc rpath (normalize-etag etag))))

(defn conditional-put-headers
  "Builds the graph PUT precondition from the last observed remote identity."
  [base-etag]
  (cond-> {"content-type" "text/plain; charset=utf-8"}
    base-etag (assoc "If-Match" base-etag)
    (nil? base-etag) (assoc "If-None-Match" "*")))

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
              (let [body-data (try
                                (js->clj (js/JSON.parse body) :keywordize-keys true)
                                (catch :default _ nil))
                    error (js/Error. (str "Server Graph API failed: " (.-status response) " " body))]
                (set! (.-status error) (.-status response))
                (set! (.-conflict? error) (= 412 (.-status response)))
                (set! (.-latestEtag error) (or (:latestEtag body-data)
                                               (get-in body-data [:stat :etag])
                                               (get-in body-data [:conflict :latestEtag])))
                (set! (.-responseBody error) body-data)
                (throw error))))))

(defn- fetch-json
  [url opts]
  (p/let [response (js/fetch url opts)
          response (assert-ok! response)
          data (response-json response)]
    (cond-> data
      (.-headers response) (assoc :response-etag
                                  (normalize-etag (.get (.-headers response) "etag"))))))

(defn- fetch-text
  [url]
  (p/let [response (js/fetch url)
          response (assert-ok! response)]
    (.text response)))

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
  [{:keys [path size type content etag] :as entry}]
  (remember-etag! path etag)
  {:name (last (string/split path #"/"))
   :path path
   :mtime (stat->mtime entry)
   :size size
   :type type
   :etag (normalize-etag etag)
   :content content})

(defn- list-entries
  [rpath]
  (p/let [result (fetch-json (api-url "/api/graph/files" rpath) nil)]
    (:entries result)))

(defn- remote-stat-etag
  "Returns the last known identity, or reads it once before the first write.
   A missing file is represented by nil so the subsequent PUT can use the
   create-only If-None-Match precondition."
  [rpath]
  (if (contains? @remote-etags rpath)
    (p/resolved (get @remote-etags rpath))
    (p/catch
     (p/let [result (fetch-json (api-url "/api/graph/stat" rpath) nil)
             stat (:stat result)
             etag (normalize-etag (or (:etag stat) (:response-etag result)))]
       (remember-etag! rpath etag)
       etag)
     (fn [error]
       (if (= 404 (aget error "status"))
         (do
           (remember-etag! rpath nil)
           nil)
         (p/rejected error))))))

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
      (p/let [base-etag (remote-stat-etag normalized)
              headers (conditional-put-headers base-etag)
              result (fetch-json (api-url "/api/graph/file" normalized)
                                 (clj->js {:method "PUT"
                                           :headers headers
                                           :body content}))
              stat (:stat result)
              etag (normalize-etag (or (:etag stat) (:response-etag result)))
              _ (remember-etag! normalized etag)]
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
            stat (:stat result)
            rpath (normalize-path default-dir fpath)
            etag (normalize-etag (or (:etag stat) (:response-etag result)))
            _ (remember-etag! rpath etag)]
      {:last-modified-at (stat->mtime stat)
       :mtime (stat->mtime stat)
       :size (:size stat)
       :path (:path stat)
       :type (:type stat)
       :etag etag}))

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
