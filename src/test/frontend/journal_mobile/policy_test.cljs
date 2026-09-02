(ns frontend.journal-mobile.policy-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.journal-mobile.policy :as policy]))

(deftest sync-trigger-policy-is-deterministic
  (testing "startup remains delayed and periodic sync remains one minute"
    (is (= {:reason :startup :delay-ms 4000}
           (policy/trigger-config :startup)))
    (is (= {:reason :interval :interval-ms 60000}
           (policy/trigger-config :periodic))))
  (testing "foreground sync is tied to a visible document"
    (is (= {:reason :visibility :visibility "visible"}
           (policy/trigger-config :foreground)))
    (is (false? (policy/foreground-visible? "hidden")))
    (is (true? (policy/foreground-visible? "visible")))))
