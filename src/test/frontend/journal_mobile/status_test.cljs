(ns frontend.journal-mobile.status-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.journal-mobile.status :as status]))

(deftest sync-phase-labels-are-observable
  (testing "offline takes precedence over a stale phase"
    (is (= "Offline" (status/phase-label :idle false))))
  (is (= "API error" (status/phase-label :error true)))
  (is (= "Conflict" (status/phase-label :conflict true)))
  (is (= "Synced" (status/phase-label :idle true))))
