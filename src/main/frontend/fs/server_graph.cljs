(ns frontend.fs.server-graph
  "Server Graph API based fs implementation for hosted Logseq journal.

   This backend keeps the normal Logseq auto-save pipeline intact: editor changes
   still call frontend.fs/write-file!, and this implementation persists those
   writes immediately to /api/graph/file on the server.

   Writes are conditional. The backend remembers the ETag of the version each
   file was loaded from (or last written as) and sends it as If-Match, or
   If-None-Match: * for a file it has never seen. When another client changed
   the file first, the server answers 412 and keeps its content; this tab then
   saves its own version under logseq/bak, loads the newer remote content and
   tells the user, instead of overwriting the other client's edits."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [frontend.db :as db]
            [frontend.fs.protocol :as protocol]
            [frontend.journal-mobile.config :as journal-mobile-config]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.graph-parser.util :as gp-util]
            [promesa.core :as p]))

(def graph-dir-prefix "server-graph://")
(def default-graph-name "journal")
(def default-dir (str graph-dir-prefix default-graph-name))
(def default-repo (str "logseq_local_" default-dir))
(def poll-interval-ms 5000)
(goog-define ENABLED "false")

(defonce *watchers (atom {}))

;; rpath -> ETag (unquoted, e.g. "sha256:...") of the content the db is based
;; on. Only updated once that content is known to be in the db: initial load,
;; our own successful writes, and remote changes the watcher has applied.
(defonce *etags (atom {}))
;; rpath -> ETag of the remote content last handed to the file watcher, so a
;; poll does not fetch the same version again while it is being applied.
(defonce *published-etags (atom {}))
;; rpath -> promise of the last queued write, so writes to one file go out one
;; at a time and each uses the ETag returned by the previous one.
(defonce *write-queues (atom {}))
(defonce *visibility-listener (atom nil))
;; Snapshot of the files the db was restored with, for the default dir watcher's
;; first poll to diff the server manifest against. Consumed once by start-watch-dir!.
(defonce *seeded-snapshot (atom nil))

(defn server-graph-dir?
  [dir]
  (and (string? dir)
       (string/starts-with? dir graph-dir-prefix)))

(defn build-enabled?
  []
  (contains? #{"1" "true"} ENABLED))

(defn enabled?
  []
  (let [location (.-location js/window)
        params (js/URLSearchParams. (.-search location))
        host (.-hostname location)]
    (and (not (journal-mobile-config/enabled?))
         (or (build-enabled?)
             (= "1" (.get params "server-graph"))
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

(defn asset-url
  "URL the hosted server serves a graph asset from. Assets are not loaded with
   the graph; the browser fetches them from /assets/* when they are shown.
   ./assets/a b.png -> /assets/a%20b.png"
  [path]
  (let [rpath (normalize-path default-dir (string/replace (or path "") #"^\.{0,2}/" ""))]
    ;; a URL, not a file system path
    (str "/" (->> (string/split rpath #"/")
                  (map js/encodeURIComponent)
                  #_{:clj-kondo/ignore [:path-invalid-construct/string-join]}
                  (string/join "/")))))

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

(defn unquote-etag
  "Normalizes an ETag header or manifest value to its bare form (sha256:...)."
  [value]
  (when (string? value)
    (let [value (string/trim value)
          value (if (string/starts-with? value "W/") (subs value 2) value)
          quoted? (and (>= (count value) 2)
                       (string/starts-with? value "\"")
                       (string/ends-with? value "\""))
          value (if quoted?
                  (string/replace (subs value 1 (dec (count value))) "\\\"" "\"")
                  value)]
      (when-not (string/blank? value)
        value))))

(defn quote-etag
  [etag]
  (str "\"" (string/replace etag "\"" "\\\"") "\""))

(defn write-precondition-headers
  "If-Match for a file this tab has loaded, If-None-Match: * for a new one."
  [etag]
  (if etag
    {"if-match" (quote-etag etag)}
    {"if-none-match" "*"}))

(defn- response-etag
  [response stat]
  (or (unquote-etag (.get (.-headers response) "etag"))
      (unquote-etag (:etag stat))))

(defn remember-etag!
  [rpath etag]
  (when-let [etag (unquote-etag etag)]
    (swap! *etags assoc rpath etag)))

(defn- forget-etag!
  [rpath]
  (swap! *etags dissoc rpath)
  (swap! *published-etags dissoc rpath))

(defn remote-content-applied!
  "Called by the file watcher once remote content fetched by the poller is in
   the db, so the next write is based on that version."
  [rpath etag]
  (remember-etag! rpath etag))

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
  (p/let [response (js/fetch url #js {:cache "no-store"})
          response (assert-ok! response)]
    (.text response)))

(defn- fetch-file
  "Reads a graph file with its ETag. Resolves to nil when it does not exist."
  [rpath]
  (p/let [response (js/fetch (api-url "/api/graph/file" rpath) #js {:cache "no-store"})]
    (when-not (= 404 (.-status response))
      (p/let [response (assert-ok! response)
              content (.text response)]
        {:content content
         :etag (response-etag response nil)}))))

(def fetch-concurrency
  "How many graph requests run at once. Fetching a graph of a few thousand files
   all at once makes the browser fail them with ERR_INSUFFICIENT_RESOURCES."
  16)

(defn map-limited
  "Like (p/all (map f coll)), but with at most limit calls of f pending at a
   time. Resolves to the results in the order of coll."
  [limit f coll]
  (let [items (vec coll)
        results (atom (vec (repeat (count items) nil)))
        cursor (atom -1)
        run-next (fn run-next []
                   (let [i (swap! cursor inc)]
                     (if (< i (count items))
                       (p/let [result (f (nth items i))]
                         (swap! results assoc i result)
                         (run-next))
                       (p/resolved nil))))]
    (p/let [_ (p/all (vec (repeatedly (min limit (count items)) run-next)))]
      @results)))

(defn- readable-stream-like?
  [content]
  (and content
       (fn? (.-getReader content))))

(defn- blob-like?
  [content]
  (and content
       (exists? js/Blob)
       (instance? js/Blob content)))

(defn- array-buffer-like?
  [content]
  (and content
       (exists? js/ArrayBuffer)
       (instance? js/ArrayBuffer content)))

(defn- array-buffer-view-like?
  [content]
  (and content
       (exists? js/ArrayBuffer)
       (fn? (.-isView js/ArrayBuffer))
       (.isView js/ArrayBuffer content)))

(defn binary-write-content?
  [content]
  (or (readable-stream-like? content)
      (blob-like? content)
      (array-buffer-like? content)
      (array-buffer-view-like? content)))

(defn db-text-write-content?
  [content]
  (string? content))

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
  [rpath content headers]
  (let [stream? (readable-stream-like? content)
        binary? (binary-write-content? content)
        content-type (if binary?
                       (or (asset-content-type rpath)
                           "application/octet-stream")
                       "text/plain; charset=utf-8")
        opts #js {:method "PUT"
                  :body content}]
    (p/let [body (if stream?
                   (readable-stream->blob content content-type)
                   content)]
      (set! (.-body opts) body)
      (set! (.-headers opts) (clj->js (assoc headers "content-type" content-type)))
      opts)))

(defn- put-file!
  [rpath content headers]
  (p/let [opts (write-request-opts rpath content headers)]
    (js/fetch (api-url "/api/graph/file" rpath) opts)))

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

(defn manifest-entry->snapshot
  [{:keys [path etag size type] :as entry}]
  (let [entry (-> entry
                  (assoc :path (normalize-path default-dir path))
                  (update :type #(or % "file")))]
    (when (allowed-file? entry)
      {:path (:path entry)
       :etag (unquote-etag etag)
       :size size
       :mtime (stat->mtime entry)
       :type (:type entry)})))

(defn manifest->snapshot
  [manifest]
  (->> (:entries manifest)
       (keep manifest-entry->snapshot)
       (map (juxt :path identity))
       (into {})))

(defn diff-manifest-snapshots
  [previous current]
  (let [previous-paths (set (keys previous))
        current-paths (set (keys current))
        sorted-entries (fn [entries]
                         (vec (sort-by :path entries)))]
    {:added (->> (set/difference current-paths previous-paths)
                 (map current)
                 sorted-entries)
     :changed (->> (set/intersection previous-paths current-paths)
                   (keep (fn [rpath]
                           (let [before (get previous rpath)
                                 after (get current rpath)]
                             (when (not= (:etag before) (:etag after))
                               after))))
                   sorted-entries)
     :deleted (->> (set/difference previous-paths current-paths)
                   (map previous)
                   sorted-entries)}))

(defn manifest-refresh-diff
  "The part of a manifest change this tab still has to fetch.

   Entries whose ETag the tab already has (its own writes) or has already
   handed to the watcher are skipped. Files whose ETag differs from the version
   the db was loaded from count as changed even without a previous snapshot,
   so the first poll after startup catches edits made during the initial load."
  [previous current known-etags published-etags]
  (let [{:keys [added changed deleted]} (if previous
                                          (diff-manifest-snapshots previous current)
                                          {:added [] :changed [] :deleted []})
        known? (fn [{:keys [path etag]}]
                 (or (= etag (get known-etags path))
                     (= etag (get published-etags path))))
        stale (keep (fn [[rpath entry]]
                      (when-let [known (get known-etags rpath)]
                        (when (not= known (:etag entry))
                          entry)))
                    current)
        added-paths (set (map :path added))
        changed (->> (concat changed stale)
                     (remove #(contains? added-paths (:path %)))
                     (map (juxt :path identity))
                     (into {})
                     vals)]
    {:added (->> added (remove known?) (sort-by :path) vec)
     :changed (->> changed (remove known?) (sort-by :path) vec)
     :deleted deleted}))

(defn conflict-backup-path
  "Where a write rejected with 412 keeps this tab's version, next to Logseq's
   own backups: logseq/bak/journals/2026_10_20/2026-10-20T08_53_44.000Z.Web.md"
  [rpath ^js date]
  (let [ext (util/get-file-ext rpath)
        stem (if (string/blank? ext)
               rpath
               (subs rpath 0 (- (count rpath) (count ext) 1)))
        stamp (string/replace (.toISOString date) ":" "_")]
    (str "logseq/bak/" stem "/" stamp ".Web" (when-not (string/blank? ext) (str "." ext)))))

(defn same-text-content?
  [a b]
  (= (string/trim (or a "")) (string/trim (or b ""))))

(defn trivial-content?
  "Content not worth keeping as a conflict backup, e.g. a fresh journal page."
  [content]
  (contains? #{"" "-" "*"} (string/trim (or content ""))))

(defn replaceable-remote-content?
  "Remote content another client created without writing anything into it,
   e.g. today's journal page. The file watcher does not load such content, so
   it is overwritten instead of being reported as a conflict on every save."
  [content journal-template]
  (or (trivial-content? content)
      (and (not (string/blank? journal-template))
           (same-text-content? content journal-template))))

(defn- file-entry->db-file
  [{:keys [path size type content etag] :as entry}]
  {:name (last (string/split path #"/"))
   :path path
   :mtime (stat->mtime entry)
   :size size
   :type type
   :content content
   :etag etag})

(defn- list-entries
  [rpath]
  (p/let [result (fetch-json (api-url "/api/graph/files" rpath) nil)]
    (:entries result)))

(defn- read-entry-content
  [entry]
  (p/let [response (js/fetch (api-url "/api/graph/file" (:path entry)) #js {:cache "no-store"})
          response (assert-ok! response)
          content (.text response)]
    (assoc entry
           :content content
           :etag (response-etag response nil))))

(defn- reset-etags!
  "The graph was (re)loaded into the db from these files."
  [files]
  (reset! *published-etags {})
  (reset! *etags (into {}
                       (keep (fn [{:keys [path etag]}]
                               (when etag [path etag])))
                       files)))

(defn- bytes->hex
  [^js buffer]
  (->> (js/Uint8Array. buffer)
       (map #(.padStart (.toString % 16) 2 "0"))
       (apply str)))

(defn content->etag
  "The ETag the server gives a file with this text content:
   sha256:<hex of the UTF-8 bytes>. Resolves to [etag byte-size]."
  [content]
  (p/let [bytes (.encode (js/TextEncoder.) content)
          digest (.digest js/crypto.subtle "SHA-256" bytes)]
    [(str "sha256:" (bytes->hex digest)) (.-byteLength bytes)]))

(defn db-files->snapshot
  "Resolves to a manifest snapshot (rpath -> entry) of the watchable files in
   the db, with the ETag each content would have on the server."
  [db-files]
  (p/let [entries (map-limited
                   fetch-concurrency
                   (fn [{:file/keys [path content last-modified-at]}]
                     (let [rpath (normalize-path default-dir path)]
                       (when (and (string? content)
                                  (allowed-file? {:path rpath :type "file"}))
                         (p/let [[etag size] (content->etag content)]
                           {:path rpath
                            :etag etag
                            :size size
                            :mtime last-modified-at
                            :type "file"}))))
                   db-files)]
    (into {} (comp (remove nil?) (map (juxt :path identity))) entries)))

(defn seed-from-db-files!
  "Treats the files of a restored db as the version this tab is based on: their
   ETags become the known ETags and the watcher's first poll diffs the manifest
   against them, so only added, changed and deleted files are fetched.
   Resolves to the number of files seeded."
  [db-files]
  (p/let [snapshot (db-files->snapshot db-files)]
    (reset-etags! (vals snapshot))
    (reset! *seeded-snapshot snapshot)
    (count snapshot)))

(defn clear-seed!
  []
  (reset! *seeded-snapshot nil))

(defn- list-files
  "Every loadable file under rpath, without content."
  [rpath]
  (p/let [entries (list-entries rpath)
          dirs (filter #(and (= "directory" (:type %))
                             (not (ignored-path? (:path %))))
                       entries)
          nested (map-limited fetch-concurrency (comp list-files :path) dirs)]
    (into (vec (filter allowed-file? entries)) cat nested)))

(defn- walk-files
  [rpath]
  (p/let [entries (list-files rpath)
          entries (map-limited fetch-concurrency read-entry-content entries)]
    (mapv file-entry->db-file entries)))

(defn- watch-event-path
  [dir rpath]
  (str (or dir default-dir) "/" rpath))

(defn- snapshot->stat
  [{:keys [path size mtime type etag]}]
  {:path path
   :size size
   :mtime mtime
   :type type
   :etag etag})

(defn- publish-watch-event!
  [dir event snapshot content]
  (state/pub-event!
   [:mobile-file-watcher/changed
    (clj->js
     {:event event
      :dir dir
      :path (watch-event-path dir (:path snapshot))
      :content content
      :stat (snapshot->stat snapshot)})]))

(defn- publish-remote-content!
  "Hands fetched remote content to the file watcher, which loads it into the
   db and then reports back through remote-content-applied!."
  [dir event snapshot {:keys [content etag]}]
  (swap! *published-etags assoc (:path snapshot) etag)
  (publish-watch-event! dir event (assoc snapshot :etag etag) content))

(defn- fetch-manifest
  "Resolves to nil when the manifest has not changed since manifest-etag."
  [manifest-etag]
  (p/let [response (js/fetch "/api/graph/manifest"
                             #js {:cache "no-store"
                                  :headers (clj->js (if manifest-etag
                                                      {"if-none-match" (quote-etag manifest-etag)}
                                                      {}))})]
    (when-not (= 304 (.-status response))
      (p/let [response (assert-ok! response)
              manifest (response-json response)]
        {:manifest manifest
         :etag (or (response-etag response nil)
                   (unquote-etag (:revision manifest)))}))))

(defn- publish-add-or-change!
  "Resolves to the path when it could not be fetched, nil otherwise."
  [dir event snapshot]
  (-> (p/let [remote (fetch-file (:path snapshot))]
        (when (and remote
                   (not= (:etag remote) (get @*etags (:path snapshot))))
          (publish-remote-content! dir event snapshot remote))
        nil)
      (p/catch (fn [error]
                 (js/console.error "Server Graph watcher fetch failed" (:path snapshot) error)
                 (:path snapshot)))))

(defn- publish-diff!
  "Resolves to the paths that failed to fetch."
  [dir {:keys [added changed deleted]}]
  (doseq [snapshot deleted]
    (publish-watch-event! dir "unlink" snapshot nil))
  (p/let [results (map-limited fetch-concurrency
                               (fn [[event snapshot]]
                                 (publish-add-or-change! dir event snapshot))
                               (concat (map (fn [snapshot] ["add" snapshot]) added)
                                       (map (fn [snapshot] ["change" snapshot]) changed)))]
    (set (remove nil? results))))

(defn- retry-failed-paths
  "Keeps the previous snapshot entry for paths that failed to fetch so the next
   poll sees them as added or changed again."
  [snapshot previous failed-paths]
  (reduce (fn [snapshot rpath]
            (if-let [entry (get previous rpath)]
              (assoc snapshot rpath entry)
              (dissoc snapshot rpath)))
          snapshot
          failed-paths))

(defn- update-watcher!
  [dir f]
  (swap! *watchers update dir (fn [watcher]
                                (when watcher
                                  (f watcher)))))

(defn- poll-watch-dir!
  [dir]
  (let [watcher (get @*watchers dir)]
    (when (and watcher (not (:polling? watcher)))
      (update-watcher! dir #(assoc % :polling? true))
      (->
       (p/let [result (fetch-manifest (:manifest-etag watcher))]
         (if-not result
           (update-watcher! dir #(assoc % :polling? false))
           (p/let [snapshot (manifest->snapshot (:manifest result))
                   previous (:snapshot (get @*watchers dir))
                   failed (publish-diff! dir (manifest-refresh-diff previous snapshot
                                                                    @*etags @*published-etags))]
             (update-watcher! dir #(assoc % :snapshot (retry-failed-paths snapshot previous failed)
                                          ;; refetch the whole manifest until every change got through
                                          :manifest-etag (when (empty? failed) (:etag result))
                                          :polling? false)))))
       (p/catch
        (fn [error]
          (js/console.error "Server Graph watcher poll failed" error)
          (update-watcher! dir #(assoc % :polling? false))))))))

(defn- poll-all-watch-dirs!
  []
  (when-not (.-hidden js/document)
    (doseq [dir (keys @*watchers)]
      (poll-watch-dir! dir))))

(defn- listen-visibility!
  "Background tabs get their timers throttled, so check for remote changes as
   soon as a stale tab is looked at again, before the user starts typing."
  []
  (when (and (nil? @*visibility-listener)
             (exists? js/document))
    (let [listener (fn [_] (poll-all-watch-dirs!))]
      (.addEventListener js/document "visibilitychange" listener)
      (.addEventListener js/window "focus" listener)
      (reset! *visibility-listener listener))))

(defn- unlisten-visibility!
  []
  (when-let [listener @*visibility-listener]
    (.removeEventListener js/document "visibilitychange" listener)
    (.removeEventListener js/window "focus" listener)
    (reset! *visibility-listener nil)))

(defn- start-watch-dir!
  [dir]
  (let [dir (or dir default-dir)]
    (when-not (contains? @*watchers dir)
      (let [interval-id (js/setInterval #(poll-watch-dir! dir) poll-interval-ms)]
        (swap! *watchers assoc dir {:interval-id interval-id
                                    :snapshot (when (= dir default-dir)
                                                (first (reset-vals! *seeded-snapshot nil)))
                                    :polling? false})
        (poll-watch-dir! dir)))))

(defn- stop-watch-dir!
  [dir]
  (let [dir (or dir default-dir)]
    (when-let [interval-id (get-in @*watchers [dir :interval-id])]
      (js/clearInterval interval-id))
    (swap! *watchers dissoc dir)
    (when (empty? @*watchers)
      (unlisten-visibility!))))

(defn- enqueue-write!
  [rpath f]
  (let [previous (get @*write-queues rpath (p/resolved nil))
        result (p/handle previous (fn [_ _] (f)))]
    (swap! *write-queues assoc rpath result)
    (p/handle result (fn [_ _]
                       (swap! *write-queues (fn [queues]
                                              (if (identical? result (get queues rpath))
                                                (dissoc queues rpath)
                                                queues)))))
    result))

(defn conflict-message
  [rpath backup-path remote-exists?]
  (str "「" rpath "」はほかの端末で"
       (if remote-exists? "更新" "削除")
       "されていたため、このタブの内容で上書きしませんでした。"
       (when remote-exists? "最新の内容を読み込み直します。")
       (when backup-path
         (str "このタブでの編集は「" backup-path "」に保存しました。"))))

(defn- notify!
  [content status]
  (state/pub-event! [:notification/show {:content content
                                         :status status
                                         :clear? false}]))

(defn- save-conflict-backup!
  "Resolves to the backup path, or nil when it could not be written."
  [rpath content]
  (let [backup-path (conflict-backup-path rpath (js/Date.))]
    (-> (p/let [response (put-file! backup-path content (write-precondition-headers nil))
                _ (assert-ok! response)]
          backup-path)
        (p/catch (fn [error]
                   (js/console.error "Server Graph conflict backup failed" backup-path error)
                   nil)))))

(declare write-file-conditionally!)

(defn- handle-text-write-conflict!
  "The server kept a newer version of rpath. Keep this tab's version under
   logseq/bak before the newer remote content replaces it in the db."
  [repo rpath content retry?]
  (p/let [remote (fetch-file rpath)]
    (cond
      (and remote (same-text-content? (:content remote) content))
      ;; The other client saved the same text; nothing to keep apart.
      (do
        (remember-etag! rpath (:etag remote))
        (db/set-file-content! repo rpath content)
        nil)

      (and remote retry?
           (replaceable-remote-content? (:content remote) (state/get-default-journal-template)))
      (do
        (remember-etag! rpath (:etag remote))
        (write-file-conditionally! repo rpath content false))

      :else
      (p/let [backup-path (when-not (trivial-content? content)
                            (save-conflict-backup! rpath content))]
        (if (and (nil? backup-path) (not (trivial-content? content)))
          ;; Without a backup, loading the remote version would drop this tab's
          ;; edits. Keep them in the db; the next save hits 412 and tries again.
          (notify! (str "「" rpath "」はほかの端末で更新されていますが、このタブの編集をバックアップできなかったため読み込み直しを保留しました。"
                        "ページを再読み込みする前に編集内容を控えてください。")
                   :error)
          (do
            (if remote
              (publish-remote-content! default-dir "change"
                                       {:path rpath :type "file" :mtime (js/Date.now)}
                                       remote)
              (forget-etag! rpath))
            (notify! (conflict-message rpath backup-path (some? remote)) :warning)))
        nil))))

(defn- write-file-conditionally!
  [repo rpath content retry?]
  (p/let [response (put-file! rpath content (write-precondition-headers (get @*etags rpath)))]
    (cond
      (not= 412 (.-status response))
      (p/let [response (assert-ok! response)
              result (response-json response)
              stat (:stat result)]
        (remember-etag! rpath (response-etag response stat))
        (when (db-text-write-content? content)
          (db/set-file-content! repo rpath content))
        (db/set-file-last-modified-at! repo rpath (stat->mtime stat))
        stat)

      (db-text-write-content? content)
      (handle-text-write-conflict! repo rpath content retry?)

      :else
      (do
        (notify! (str "「" rpath "」はすでにサーバーにあるため上書きしませんでした。") :warning)
        nil))))

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
                                 #js {:method "DELETE"})
              _ (assert-ok! response)]
        (forget-etag! rpath)
        nil)))

  (rmdir! [_this _dir]
    nil)

  (read-file [_this dir rpath _opts]
    (fetch-text (api-url "/api/graph/file" (normalize-path dir rpath))))

  (write-file! [_this repo dir rpath content _opts]
    (let [normalized (normalize-path dir rpath)]
      (enqueue-write! normalized #(write-file-conditionally! repo normalized content true))))

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
      (reset-etags! files)
      {:path (or dir default-dir)
       :files files}))

  (get-files [_this _dir]
    (p/let [files (walk-files "")]
      (reset-etags! files)
      {:path default-dir
       :files files}))

  (watch-dir! [_this dir _options]
    (listen-visibility!)
    (start-watch-dir! dir))

  (unwatch-dir! [_this dir]
    (stop-watch-dir! dir)))
