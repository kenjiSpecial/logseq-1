(ns frontend.commands-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.commands :as commands]))

(deftest kura-task-slash-command-test
  (testing "/kura inserts the kura task template"
    (let [command (->> (commands/commands-map identity)
                       (filter #(= "kura" (first %)))
                       first)]
      (is (= "kura" (first command)))
      (is (= [[:editor/input "LATER #kuratask" {:last-pattern "/"}]]
             (second command))))))
