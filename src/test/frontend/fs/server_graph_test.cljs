(ns frontend.fs.server-graph-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.fs.server-graph :as server-graph]))

(deftest server-graph-dir-detection
  (is (true? (server-graph/server-graph-dir? "server-graph://journal")))
  (is (false? (server-graph/server-graph-dir? "journal"))))

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

(deftest manifest-entry-snapshot-keeps-watchable-text-files
  (is (= {:path "pages/today.md"
          :etag "\"abc\""
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
                           :etag "\"home\""
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
