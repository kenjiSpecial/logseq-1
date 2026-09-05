(ns frontend.journal-mobile.manifest-test
  (:require [clojure.test :refer [is testing]]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.journal-mobile.manifest :as manifest]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(deftest-async save-continues-when-sync-directory-already-exists
  (testing "an existing app-private sync directory does not block manifest persistence"
    (let [mkdir-options (atom nil)
          writes (atom [])]
      (test-helper/with-reset reset
        [journal-config/enabled? (constantly true)
         #_{:clj-kondo/ignore [:private-call]}
         manifest/mkdir! (fn [options]
                           (reset! mkdir-options (js->clj options :keywordize-keys true))
                           (p/rejected (js/Error. "Error: Directory exists")))
         #_{:clj-kondo/ignore [:private-call]}
         manifest/write-file! (fn [options]
                               (swap! writes conj (js->clj options :keywordize-keys true))
                               (p/resolved nil))]
        (-> (manifest/save! {:remote {:revision 1 :entries []}})
            (p/then (fn [_]
                      (is (= true (:recursive @mkdir-options)))
                      (is (= 1 (count @writes)))
                      (is (= (manifest/manifest-path) (:path (first @writes))))
                      (is (= {:remote {:revision 1 :entries []}}
                             (js->clj (js/JSON.parse (:data (first @writes)))
                                      :keywordize-keys true)))))
            (p/finally (fn []
                         (reset))))))))
