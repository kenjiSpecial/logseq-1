(ns frontend.fs.server-graph-test
  (:require [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [frontend.db :as db]
            [frontend.fs.protocol :as protocol]
            [frontend.fs.server-graph :as server-graph]
            [frontend.state :as state]
            [promesa.core :as p]))

(deftest server-graph-dir-detection
  (is (true? (server-graph/server-graph-dir? "server-graph://journal")))
  (is (false? (server-graph/server-graph-dir? "journal"))))

(deftest server-graph-build-flag-defaults-off
  (is (false? (server-graph/build-enabled?))))

(deftest path-normalization-removes-server-graph-prefix
  (is (= "pages/today.md"
         (server-graph/normalize-path "server-graph://journal" "pages/today.md")))
  (is (= "pages/today.md"
         (server-graph/normalize-path "server-graph://journal" "server-graph://journal/pages/today.md")))
  (is (= ""
         (server-graph/normalize-path "server-graph://journal" "server-graph://journal"))))

(deftest api-url-encodes-path-query
  (is (= "/api/graph/file?path=pages%2F%E4%BB%8A%E6%97%A5.md"
         (server-graph/api-url "/api/graph/file" "pages/今日.md"))))

(deftest write-content-classification
  (testing "text writes are mirrored into DB file content"
    (is (true? (server-graph/db-text-write-content? "page text"))))
  (testing "binary asset writes are not mirrored into DB file content"
    (is (false? (server-graph/db-text-write-content?
                 #js {:getReader (fn [] nil)}))))
  (testing "binary-like bodies are sent as asset writes"
    (is (true? (server-graph/binary-write-content?
                #js {:getReader (fn [] nil)})))
    (when (exists? js/Blob)
      (is (true? (server-graph/binary-write-content?
                  (js/Blob. #js ["png"] #js {:type "image/png"})))))))

(deftest manifest-entry-snapshot-keeps-watchable-text-files
  (is (= {:path "pages/today.md"
          :etag "abc"
          :size 12
          :mtime 1000
          :type "file"}
         (server-graph/manifest-entry->snapshot
          {:path "pages/today.md"
           :etag "\"abc\""
           :size 12
           :mtime 1000
           :type "file"}))))

(deftest manifest-entry-snapshot-ignores-unwatchable-paths
  (testing "binary assets are ignored"
    (is (nil? (server-graph/manifest-entry->snapshot
               {:path "assets/photo.png"
                :etag "\"png\""
                :type "file"}))))
  (testing "ignored Logseq paths are ignored"
    (is (nil? (server-graph/manifest-entry->snapshot
               {:path "logseq/graphs-txid.edn"
                :etag "\"txid\""
                :type "file"}))))
  (testing "hidden paths are ignored"
    (is (nil? (server-graph/manifest-entry->snapshot
               {:path ".shadow-cljs/build.edn"
                :etag "\"shadow\""
                :type "file"})))))

(deftest diff-manifest-snapshots-detects-add-change-and-unlink
  (let [before {"journals/2026_05_20.md" {:path "journals/2026_05_20.md"
                                          :etag "\"old\""
                                          :mtime 1
                                          :size 4
                                          :type "file"}
                "pages/removed.md" {:path "pages/removed.md"
                                    :etag "\"gone\""
                                    :mtime 1
                                    :size 7
                                    :type "file"}}
        after {"journals/2026_05_20.md" {:path "journals/2026_05_20.md"
                                         :etag "\"new\""
                                         :mtime 2
                                         :size 5
                                         :type "file"}
               "pages/added.md" {:path "pages/added.md"
                                 :etag "\"added\""
                                 :mtime 3
                                 :size 9
                                 :type "file"}}
        diff (server-graph/diff-manifest-snapshots before after)]
    (is (= ["pages/added.md"] (mapv :path (:added diff))))
    (is (= ["journals/2026_05_20.md"] (mapv :path (:changed diff))))
    (is (= ["pages/removed.md"] (mapv :path (:deleted diff))))))

(deftest manifest-snapshot-filters-ignored-entries
  (is (= {"pages/home.md" {:path "pages/home.md"
                           :etag "home"
                           :size 10
                           :mtime 1
                           :type "file"}}
         (server-graph/manifest->snapshot
          {:entries [{:path "pages/home.md"
                      :etag "\"home\""
                      :size 10
                      :mtime 1
                      :type "file"}
                     {:path "assets/image.webp"
                      :etag "\"image\""
                      :type "file"}
                     {:path "logseq/bak/home.md"
                      :etag "\"bak\""
                      :type "file"}]}))))

(deftest etag-values-are-normalized
  (is (= "sha256:abc" (server-graph/unquote-etag "\"sha256:abc\"")))
  (is (= "sha256:abc" (server-graph/unquote-etag "W/\"sha256:abc\"")))
  (is (= "sha256:abc" (server-graph/unquote-etag "sha256:abc")))
  (is (nil? (server-graph/unquote-etag nil)))
  (is (= "\"sha256:abc\"" (server-graph/quote-etag "sha256:abc"))))

(deftest write-preconditions
  (testing "a file loaded by this tab is only replaced if nobody changed it"
    (is (= {"if-match" "\"sha256:abc\""}
           (server-graph/write-precondition-headers "sha256:abc"))))
  (testing "a file this tab has never seen is only created if it does not exist"
    (is (= {"if-none-match" "*"}
           (server-graph/write-precondition-headers nil)))))

(deftest manifest-refresh-diff-skips-known-versions
  (let [entry (fn [rpath etag] {:path rpath :etag etag :type "file"})
        previous {"journals/a.md" (entry "journals/a.md" "a1")
                  "journals/b.md" (entry "journals/b.md" "b1")}
        current {"journals/a.md" (entry "journals/a.md" "a2")
                 "journals/b.md" (entry "journals/b.md" "b2")
                 "pages/new.md" (entry "pages/new.md" "n1")}]
    (testing "own writes and versions already handed to the watcher are skipped"
      (let [diff (server-graph/manifest-refresh-diff previous current
                                                     {"journals/a.md" "a2"}
                                                     {"pages/new.md" "n1"})]
        (is (= [] (:added diff)))
        (is (= ["journals/b.md"] (mapv :path (:changed diff))))))
    (testing "the first poll compares the manifest with the versions loaded at startup"
      (let [diff (server-graph/manifest-refresh-diff nil current
                                                     {"journals/a.md" "a1"
                                                      "journals/b.md" "b2"}
                                                     {})]
        (is (= [] (:added diff)))
        (is (= ["journals/a.md"] (mapv :path (:changed diff))))
        (is (= [] (:deleted diff)))))))

(deftest conflict-backup-path-sits-next-to-logseq-backups
  (is (= "logseq/bak/journals/2026_10_20/2026-10-20T08_53_44.000Z.Web.md"
         (server-graph/conflict-backup-path "journals/2026_10_20.md"
                                            (js/Date. "2026-10-20T08:53:44.000Z")))))

(deftest trivial-content-is-not-backed-up
  (is (true? (server-graph/trivial-content? "-\n")))
  (is (true? (server-graph/trivial-content? "")))
  (is (false? (server-graph/trivial-content? "- memo"))))

(deftest untouched-remote-pages-are-replaceable
  (is (true? (server-graph/replaceable-remote-content? "-" nil)))
  (is (true? (server-graph/replaceable-remote-content? "- [[kura]]\n" "- [[kura]]")))
  (is (false? (server-graph/replaceable-remote-content? "- written on mac" "- [[kura]]")))
  (is (false? (server-graph/replaceable-remote-content? "- written on mac" ""))))

;; write-file! against a stubbed /api/graph/file

(def repo "logseq_local_server-graph://journal")
(def dir "server-graph://journal")

(defn- response
  [status body headers]
  (js/Response. body #js {:status status :headers (clj->js headers)}))

(defn- ok-write
  [etag]
  (response 200 (js/JSON.stringify (clj->js {:ok true :stat {:etag etag :mtime 1}}))
            {"etag" (server-graph/quote-etag etag)}))

(defn- with-server!
  "Runs (f requests) with fetch answered by (handler request). Each request is
   {:url :method :headers :body}; db and event side effects are recorded."
  [handler f]
  (let [requests (atom [])
        events (atom [])
        db-writes (atom [])
        original-fetch js/globalThis.fetch
        original-set-file-content db/set-file-content!
        original-set-mtime db/set-file-last-modified-at!
        original-pub-event state/pub-event!
        restore! (fn []
                   (set! js/globalThis.fetch original-fetch)
                   (set! db/set-file-content! original-set-file-content)
                   (set! db/set-file-last-modified-at! original-set-mtime)
                   (set! state/pub-event! original-pub-event))]
    (reset! server-graph/*etags {})
    (reset! server-graph/*published-etags {})
    (set! js/globalThis.fetch
          (fn [url ^js opts]
            (let [request {:url url
                           :method (or (some-> opts .-method) "GET")
                           :headers (js->clj (some-> opts .-headers))
                           :body (some-> opts .-body)}]
              (swap! requests conj request)
              (js/Promise.resolve (handler request)))))
    (set! db/set-file-content! (fn [_repo rpath content & _] (swap! db-writes conj [rpath content])))
    (set! db/set-file-last-modified-at! (fn [& _] nil))
    (set! state/pub-event! (fn [event] (swap! events conj event)))
    (p/handle (f {:requests requests :events events :db-writes db-writes})
              (fn [_ error]
                (restore!)
                (when error (is (nil? error) (str error)))))))

(defn- write!
  [rpath content]
  (protocol/write-file! (server-graph/->ServerGraphFs) repo dir rpath content nil))

(defn- puts-to
  [requests rpath]
  (filter #(and (= "PUT" (:method %))
                (= (server-graph/api-url "/api/graph/file" rpath) (:url %)))
          @requests))

(deftest write-uses-if-match-and-keeps-the-returned-etag
  (async done
    (-> (with-server!
          (fn [_] (ok-write "sha256:v2"))
          (fn [{:keys [requests]}]
            (server-graph/remember-etag! "journals/a.md" "sha256:v1")
            (p/let [_ (write! "journals/a.md" "- one")
                    _ (write! "journals/a.md" "- one\n- two")
                    _ (write! "pages/new.md" "- new")]
              (let [[first-put second-put] (puts-to requests "journals/a.md")]
                (is (= "\"sha256:v1\"" (get-in first-put [:headers "if-match"])))
                (is (= "\"sha256:v2\"" (get-in second-put [:headers "if-match"]))))
              (is (= "*" (get-in (first (puts-to requests "pages/new.md")) [:headers "if-none-match"])))
              (is (nil? (get-in (first (puts-to requests "pages/new.md")) [:headers "if-match"]))))))
        (p/handle (fn [_ _] (done))))))

(deftest queued-writes-wait-for-the-previous-etag
  (async done
    (-> (with-server!
          (let [n (atom 0)]
            (fn [_] (ok-write (str "sha256:v" (swap! n inc)))))
          (fn [{:keys [requests]}]
            (server-graph/remember-etag! "journals/a.md" "sha256:v0")
            ;; fired without waiting, like quick successive auto-saves
            (p/let [_ (p/all [(write! "journals/a.md" "- 1")
                              (write! "journals/a.md" "- 1\n- 2")])]
              (is (= ["\"sha256:v0\"" "\"sha256:v1\""]
                     (mapv #(get-in % [:headers "if-match"])
                           (puts-to requests "journals/a.md")))))))
        (p/handle (fn [_ _] (done))))))

(defn- conflict-server
  "412 for the stale write, GET returns the newer remote content."
  [remote-content {:keys [backup-status] :or {backup-status 200}}]
  (fn [{:keys [url method]}]
    (cond
      (and (= "PUT" method) (string/includes? url "logseq%2Fbak%2F"))
      (if (= 200 backup-status)
        (ok-write "sha256:bak")
        (response backup-status "{}" {}))

      (= "PUT" method)
      (response 412 (js/JSON.stringify (clj->js {:error "ETag conflict"
                                                 :stat {:etag "sha256:remote"}}))
                {"etag" "\"sha256:remote\""})

      :else
      (response 200 remote-content {"etag" "\"sha256:remote\""}))))

(deftest stale-write-keeps-remote-and-backs-up-local-edits
  (async done
    (-> (with-server!
          (conflict-server "- from mac\n- appended on mac" {})
          (fn [{:keys [requests events]}]
            (server-graph/remember-etag! "journals/2026_10_20.md" "sha256:old")
            (p/let [_ (write! "journals/2026_10_20.md" "- from mac\n- typed in web")]
              (testing "the stale content is sent once, conditionally, and not retried"
                (is (= 1 (count (puts-to requests "journals/2026_10_20.md")))))
              (testing "this tab's version is kept under logseq/bak without overwriting"
                (let [backup (first (filter #(string/includes? (:url %) "logseq%2Fbak%2Fjournals%2F2026_10_20%2F")
                                            @requests))]
                  (is (some? backup))
                  (is (= "*" (get-in backup [:headers "if-none-match"])))
                  (is (= "- from mac\n- typed in web" (:body backup)))))
              (testing "the newer remote content is handed to the watcher"
                (let [[_ ^js change] (first (filter #(= :mobile-file-watcher/changed (first %)) @events))]
                  (is (= "change" (.-event change)))
                  (is (= "- from mac\n- appended on mac" (.-content change)))
                  (is (= "sha256:remote" (.. change -stat -etag)))))
              (testing "the user is told"
                (is (some #(and (= :notification/show (first %))
                                (= :warning (:status (second %))))
                          @events)))
              (testing "the etag only moves once the watcher applied the remote content"
                (is (= "sha256:old" (get @server-graph/*etags "journals/2026_10_20.md")))
                (server-graph/remote-content-applied! "journals/2026_10_20.md" "sha256:remote")
                (is (= "sha256:remote" (get @server-graph/*etags "journals/2026_10_20.md")))))))
        (p/handle (fn [_ _] (done))))))

(deftest conflict-with-identical-content-just-adopts-the-etag
  (async done
    (-> (with-server!
          (conflict-server "- same\n" {})
          (fn [{:keys [requests events]}]
            (server-graph/remember-etag! "journals/a.md" "sha256:old")
            (p/let [_ (write! "journals/a.md" "- same")]
              (is (empty? (filter #(string/includes? (:url %) "logseq%2Fbak") @requests)))
              (is (empty? @events))
              (is (= "sha256:remote" (get @server-graph/*etags "journals/a.md"))))))
        (p/handle (fn [_ _] (done))))))

(deftest conflict-without-backup-keeps-local-edits-in-the-db
  (async done
    (-> (with-server!
          (conflict-server "- remote" {:backup-status 500})
          (fn [{:keys [events]}]
            (server-graph/remember-etag! "journals/a.md" "sha256:old")
            (p/let [_ (write! "journals/a.md" "- local edit")]
              (is (empty? (filter #(= :mobile-file-watcher/changed (first %)) @events)))
              (is (some #(and (= :notification/show (first %))
                              (= :error (:status (second %))))
                        @events))
              (is (= "sha256:old" (get @server-graph/*etags "journals/a.md"))))))
        (p/handle (fn [_ _] (done))))))

(deftest conflict-with-an-untouched-remote-page-overwrites-it-once
  (async done
    (-> (with-server!
          (let [puts (atom 0)]
            (fn [{:keys [method headers]}]
              (cond
                (and (= "PUT" method) (= 1 (swap! puts inc)))
                (response 412 "{}" {"etag" "\"sha256:empty\""})

                (= "PUT" method)
                (if (= "\"sha256:empty\"" (get headers "if-match"))
                  (ok-write "sha256:mine")
                  (response 412 "{}" {}))

                :else
                (response 200 "-\n" {"etag" "\"sha256:empty\""}))))
          (fn [{:keys [requests events]}]
            (p/let [_ (write! "journals/2026_10_21.md" "- typed in web")]
              (is (= ["*" nil] (mapv #(get-in % [:headers "if-none-match"])
                                     (puts-to requests "journals/2026_10_21.md"))))
              (is (empty? (filter #(string/includes? (:url %) "logseq%2Fbak") @requests)))
              (is (empty? @events))
              (is (= "sha256:mine" (get @server-graph/*etags "journals/2026_10_21.md"))))))
        (p/handle (fn [_ _] (done))))))
