(ns frontend.mobile.calendar-state-test
  (:require [cljs.test :refer [deftest is]]
            [cljs-time.core :as t]
            [frontend.mobile.calendar-state :as calendar-state]
            [frontend.state :as state]))

(deftest selected-date->journal-page-name-uses-default-time-zone-and-journal-format
  (with-redefs [state/get-date-formatter (constantly "yyyy_MM_dd")]
    (is (= "2026_05_17"
           (calendar-state/selected-date->journal-page-name (t/date-time 2026 5 17))))))

(deftest open-calendar-state-can-be-opened-and-closed
  (calendar-state/close-calendar!)
  (is (false? (calendar-state/calendar-open?)))
  (calendar-state/open-calendar!)
  (is (true? (calendar-state/calendar-open?)))
  (calendar-state/close-calendar!)
  (is (false? (calendar-state/calendar-open?))))
