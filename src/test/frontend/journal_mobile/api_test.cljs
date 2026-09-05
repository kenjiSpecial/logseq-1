(ns frontend.journal-mobile.api-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.journal-mobile.api :as api]))

(deftest android-edit-uses-remote-etag-precondition
  (testing "an edit of a known remote file uses If-Match"
    (is (= {"content-type" "text/plain; charset=utf-8"
            "If-Match" "sha256:old"}
           (api/conditional-put-headers "sha256:old"))))
  (testing "a new file uses create-only semantics"
    (is (= {"content-type" "text/plain; charset=utf-8"
            "If-None-Match" "*"}
           (api/conditional-put-headers nil)))))
