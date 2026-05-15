(ns frontend.fs.server-graph-test
  (:require [clojure.test :refer [deftest is]]
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
