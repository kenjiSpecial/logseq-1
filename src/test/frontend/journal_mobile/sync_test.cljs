(ns frontend.journal-mobile.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [frontend.journal-mobile.sync :as sync]))

(deftest content-identity-prevents-mtime-only-pulls
  (testing "Android and server mtime representations may differ"
    (is (= {:downloads []
            :skipped-dirty []}
           (sync/pull-candidates
            {:entries [{:path "journals/today.md"
                        :type "file"
                        :size 8
                        :mtime 1000
                        :etag "sha256:same"}]}
            {:entries [{:path "journals/today.md"
                        :type "file"
                        :size 8
                        :mtime "2026-09-02T00:00:00.000Z"
                        :etag "sha256:same"}]}
            #{})))))

(deftest etag-change-is-a-pull-candidate
  (let [result (sync/pull-candidates
                {:entries [{:path "journals/today.md"
                            :type "file"
                            :size 8
                            :mtime 1000
                            :etag "sha256:old"}]}
                {:entries [{:path "journals/today.md"
                            :type "file"
                            :size 20
                            :mtime 2000
                            :etag "sha256:new"}]}
                #{})]
    (is (= ["journals/today.md"] (mapv :path (:downloads result))))
    (is (= :changed (:reason (first (:downloads result)))))))

(deftest queued-local-edits-are-protected-from-pull
  (let [result (sync/pull-candidates
                {:entries [{:path "journals/today.md"
                            :type "file"
                            :etag "sha256:old"}]}
                {:entries [{:path "journals/today.md"
                            :type "file"
                            :etag "sha256:new"}
                           {:path "pages/remote.md"
                            :type "file"
                            :etag "sha256:remote"}]}
                #{"journals/today.md"})]
    (is (= ["pages/remote.md"] (mapv :path (:downloads result))))
    (is (= ["journals/today.md"] (:skipped-dirty result)))))

(deftest remote-delete-is-a-retain-local-conflict
  (testing "a local copy survives when the previous remote entry disappears"
    (is (= [{:path "journals/old.md"
             :type :remote-delete
             :action :retain-local
             :queued? false}]
           (sync/remote-delete-conflicts
            {:entries [{:path "journals/old.md" :type "file" :etag "sha256:old"}]}
            {:entries []}
            {:entries [{:path "journals/old.md" :type "file" :etag "sha256:local"}]}
            #{}))))
  (testing "a queued local copy is retained too, with its dirty state visible"
    (let [result (sync/remote-delete-conflicts
                  {:entries [{:path "journals/dirty.md" :type "file" :etag "sha256:old"}]}
                  {:entries []}
                  {:entries [{:path "journals/dirty.md" :type "file" :etag "sha256:local"}]}
                  #{"journals/dirty.md"})]
      (is (= [{:path "journals/dirty.md"
               :type :remote-delete
               :action :retain-local
               :queued? true}]
             result))
      (is (every? #(= :retain-local (:action %)) result)))))

(deftest upload-412-is-retryable-and-retains-queue-item
  (let [result (sync/classify-upload-failure
                "journals/today.md"
                412
                "sha256:remote-new"
                "precondition failed")]
    (is (= :conflict (:status result)))
    (is (true? (:retryable? result)))
    (is (= :retain (:queue-action result)))
    (is (not= :complete (:queue-action result)))
    (is (= "sha256:remote-new" (:latestEtag result)))))
