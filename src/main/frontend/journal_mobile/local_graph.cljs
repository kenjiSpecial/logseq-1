(ns frontend.journal-mobile.local-graph
  "Creates and opens the app-private local Journal graph for Android."
  (:require ["@capacitor/filesystem" :refer [Directory Encoding Filesystem]]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.journal-mobile.dirty-queue :as dirty-queue]
            [frontend.util :as util]
            [logseq.common.path :as path]
            [logseq.graph-parser.util :as gp-util]
            [promesa.core :as p]))

(def minimal-config-edn
  "{:preferred-format :markdown
 :journals-directory \"journals\"
 :pages-directory \"pages\"}
")

(defn enabled?
  []
  (journal-config/enabled?))

(def filesystem-directory
  (.-Data Directory))

(defn- validate-local-graph-dir!
  [dir]
  (let [dir (when (string? dir) (string/trim dir))
        segments (some-> dir (string/split #"/"))]
    (when (or (string/blank? dir)
              (string/starts-with? dir "/")
              (boolean (re-find #"^[A-Za-z]:" dir))
              (string/starts-with? (string/lower-case dir) "file://")
              (string/includes? dir "\\")
              (some #{".."} segments))
      (throw (js/Error. (str "Invalid Journal local graph dir: " (pr-str dir)))))
    dir))

(defn graph-dir
  []
  (validate-local-graph-dir! (journal-config/local-graph-dir)))

(defn graph-repo
  []
  (str journal-config/local-db-prefix (graph-dir)))

(defn repo-entry
  []
  (assoc (journal-config/repo-entry)
         :url (graph-repo)
         :root (graph-dir)))

(defn- filesystem-opts
  [fpath opts]
  (clj->js (merge {:path fpath
                   :directory filesystem-directory}
                  opts)))

(defn- graph-path
  ([root]
   root)
  ([root rpath]
   (if (string/blank? rpath)
     root
     (path/path-join root rpath))))

(defn- child-path
  [parent name]
  (if (string/blank? parent)
    name
    (path/path-join parent name)))

(defn- stat
  [fpath]
  (.stat Filesystem (filesystem-opts fpath nil)))

(defn- <dir-exists?
  [fpath]
  (p/catch
   (p/let [stat (stat fpath)]
     (= "directory" (:type (bean/->clj stat))))
   (fn [_] false)))

(defn- <file-exists?
  [fpath]
  (p/catch
   (p/let [stat (stat fpath)]
     (= "file" (:type (bean/->clj stat))))
   (fn [_] false)))

(defn- mkdir-recur!
  [fpath]
  (p/let [exists? (<dir-exists? fpath)]
    (when-not exists?
      (.mkdir Filesystem (filesystem-opts fpath {:recursive true})))))

(defn- write-file-if-missing!
  [fpath content]
  (p/let [exists? (<file-exists? fpath)]
    (when-not exists?
      (.writeFile Filesystem (filesystem-opts fpath
                                              {:data content
                                               :encoding (.-UTF8 Encoding)
                                               :recursive true})))))

(defn ensure!
  "Ensures the stable app-private Journal graph skeleton exists."
  []
  (let [root (graph-dir)]
    (p/do!
     (mkdir-recur! root)
     (mkdir-recur! (path/path-join root "journals"))
     (mkdir-recur! (path/path-join root "pages"))
     (mkdir-recur! (path/path-join root "assets"))
     (mkdir-recur! (path/path-join root "logseq"))
     (mkdir-recur! (dirty-queue/sync-dir))
     (write-file-if-missing! (path/path-join root "logseq/config.edn")
                             minimal-config-edn)
     (dirty-queue/initialize!)
     {:repo (graph-repo)
      :dir root})))

(defn- read-file
  [fpath]
  (-> (p/chain (.readFile Filesystem (filesystem-opts fpath
                                                      {:encoding (.-UTF8 Encoding)}))
               #(js->clj % :keywordize-keys true)
               :data)
      (p/catch (fn [_] nil))))

(defn read-file-content
  [rpath]
  (if-let [rpath (dirty-queue/normalize-relative-path rpath)]
    (if (dirty-queue/ignored-path? rpath)
      (p/rejected (js/Error. (str "Ignored Journal file path: " (pr-str rpath))))
      (p/let [content (read-file (path/path-join (graph-dir) rpath))]
        (if (nil? content)
          (p/rejected (js/Error. (str "Unable to read Journal file content: " (pr-str rpath))))
          content)))
    (p/rejected (js/Error. (str "Invalid Journal file path: " (pr-str rpath))))))

(defn- readdir
  [fpath]
  (-> (p/chain (.readdir Filesystem (filesystem-opts fpath nil))
               #(js->clj % :keywordize-keys true)
               :files)
      (p/catch (fn [_] nil))))

(def allowed-exts #{"md" "markdown" "org" "edn" "css" "js" "excalidraw"})

(defn- ignored-path?
  [rpath]
  (let [segments (string/split (or rpath "") #"/")]
    (or (some #(and (not= "." %)
                    (string/starts-with? % "."))
              segments)
        (string/starts-with? rpath "logseq/bak/")
        (string/starts-with? rpath "logseq/version-files/"))))

(defn files
  "Reads graph files into the shape expected by repo-handler/load-new-repo-to-db!."
  []
  (let [root (graph-dir)]
    (p/let [entries (p/loop [result []
                             dirs [""]]
                      (if (empty? dirs)
                        result
                        (p/let [rdir (first dirs)
                                dir (graph-path root rdir)
                                children (or (readdir dir) [])
                                children (remove (fn [{:keys [name]}]
                                                   (string/starts-with? name "."))
                                                 children)
                                child-dirs (->> children
                                                (filter #(= "directory" (:type %)))
                                                (mapv (fn [{:keys [name]}]
                                                        (child-path rdir name))))
                                child-files (->> children
                                                 (filter #(= "file" (:type %)))
                                                 (mapv (fn [{:keys [name size mtime]}]
                                                         (let [rpath (gp-util/path-normalize (child-path rdir name))
                                                               fpath (graph-path root rpath)]
                                                           (when (contains? allowed-exts (util/get-file-ext rpath))
                                                             (p/let [content (read-file fpath)]
                                                               (when-not (ignored-path? rpath)
                                                                 {:file/path rpath
                                                                  :file/content content
                                                                  :file/size size
                                                                  :file/last-modified-at mtime})))))))
                                child-files (p/all child-files)]
                          (p/recur (concat result (remove nil? child-files))
                                   (concat (rest dirs) child-dirs)))))]
      (vec entries))))
