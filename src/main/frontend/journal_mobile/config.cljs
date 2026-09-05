(ns frontend.journal-mobile.config
  "Build-time configuration for the Journal Android local-first app mode.")

(goog-define ENABLED "false")
(goog-define API-BASE-URL "http://100.74.89.18:3060")
(goog-define LOCAL-GRAPH-DIR "journal/graph")
(goog-define LOCAL-GRAPH-NAME "Journal")

(def local-db-prefix "logseq_local_")

(defn enabled?
  []
  (contains? #{"1" "true"} ENABLED))

(defn api-base-url
  []
  API-BASE-URL)

(defn local-graph-dir
  []
  LOCAL-GRAPH-DIR)

(defn local-graph-name
  []
  LOCAL-GRAPH-NAME)

(defn local-graph-repo
  []
  (str local-db-prefix (local-graph-dir)))

(defn repo-entry
  []
  {:url (local-graph-repo)
   :nfs? true
   :journal-mobile? true})
