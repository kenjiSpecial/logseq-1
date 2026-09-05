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

(deftest graph-put-always-has-a-precondition
  (testing "existing files use the observed identity"
    (is (= {"content-type" "text/plain; charset=utf-8"
            "If-Match" "sha256:old"}
           (server-graph/conditional-put-headers "sha256:old"))))
  (testing "new files use create-only semantics"
    (is (= {"content-type" "text/plain; charset=utf-8"
            "If-None-Match" "*"}
           (server-graph/conditional-put-headers nil)))))
