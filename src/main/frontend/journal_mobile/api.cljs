(ns frontend.journal-mobile.api
  "Small Journal sync API client. Network failures are handled by callers so
  app-private local graph boot never depends on this namespace succeeding."
  (:require [clojure.string :as string]
            [frontend.journal-mobile.config :as journal-config]
            [frontend.journal-mobile.dirty-queue :as dirty-queue]
            [promesa.core :as p]))

(defn- api-base-url
  []
  (string/replace (journal-config/api-base-url) #"/+$" ""))

(defn- api-url
  ([endpoint]
   (str (api-base-url) endpoint))
  ([endpoint rpath]
   (str (api-url endpoint) "?path=" (js/encodeURIComponent rpath))))

(defn- assert-ok!
  [response]
  (if (.-ok response)
    response
    (p/then (.text response)
            (fn [body]
              (throw (js/Error. (str "Journal API failed: " (.-status response) " " body)))))))

(defn- response-json
  [response]
  (p/let [body (.json response)]
    (js->clj body :keywordize-keys true)))

(defn get-manifest!
  []
  (p/let [response (js/fetch (api-url "/api/graph/manifest"))
          response (assert-ok! response)]
    (response-json response)))

(defn get-file!
  "Fetches one graph file. Returns {:status 304} when If-None-Match succeeds."
  ([rpath]
   (get-file! rpath nil))
  ([rpath {:keys [etag]}]
   (if-let [rpath (dirty-queue/normalize-relative-path rpath)]
     (if (dirty-queue/ignored-path? rpath)
       (p/rejected (js/Error. (str "Ignored Journal file path: " (pr-str rpath))))
       (p/let [headers (cond-> {}
                         etag (assoc "If-None-Match" etag))
               response (js/fetch (api-url "/api/graph/file" rpath)
                                  (clj->js (cond-> {}
                                            (seq headers) (assoc :headers headers))))]
         (if (= 304 (.-status response))
           {:status 304
            :path rpath
            :etag etag}
           (p/let [response (assert-ok! response)
                   content (.text response)]
             {:status (.-status response)
              :path rpath
              :etag (.get (.-headers response) "etag")
              :content content}))))
     (p/rejected (js/Error. (str "Invalid Journal file path: " (pr-str rpath)))))))

(defn put-file!
  [rpath content {:keys [baseRemoteEtag]}]
  (cond
    (nil? content)
    (p/rejected (js/Error. (str "Refusing to upload nil Journal file content: " (pr-str rpath))))

    (not (string? content))
    (p/rejected (js/Error. (str "Invalid Journal file content for upload: " (pr-str rpath))))

    :else
    (if-let [rpath (dirty-queue/normalize-relative-path rpath)]
      (if (dirty-queue/ignored-path? rpath)
        (p/rejected (js/Error. (str "Ignored Journal file path: " (pr-str rpath))))
        (p/let [headers (cond-> {"content-type" "text/plain; charset=utf-8"}
                          baseRemoteEtag (assoc "If-Match" baseRemoteEtag))
                response (js/fetch (api-url "/api/graph/file" rpath)
                                   (clj->js {:method "PUT"
                                             :headers headers
                                             :body content}))
                response (assert-ok! response)]
          {:status (.-status response)
           :path rpath
           :etag (.get (.-headers response) "etag")}))
      (p/rejected (js/Error. (str "Invalid Journal file path: " (pr-str rpath)))))))
