(ns frontend.mobile.calendar-state
  (:require [cljs-time.core :as t]
            [frontend.date :as date]))

(defonce ^:private *calendar-open? (atom false))
(defonce ^:private *selected-date (atom nil))

(defn selected-date->journal-page-name
  [selected-date]
  (date/journal-name (t/to-default-time-zone selected-date)))

(defn calendar-open?
  []
  (true? @*calendar-open?))

(defn calendar-open-state
  []
  *calendar-open?)

(defn selected-date
  []
  @*selected-date)

(defn selected-date-state
  []
  *selected-date)

(defn set-selected-date!
  [selected-date]
  (reset! *selected-date selected-date))

(defn open-calendar!
  []
  (set-selected-date! (t/today))
  (reset! *calendar-open? true))

(defn close-calendar!
  []
  (reset! *calendar-open? false))
