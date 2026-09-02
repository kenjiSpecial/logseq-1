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

(defn normalize-etag
  "Normalizes an HTTP ETag header to the unquoted identity used in manifests.
   The server accepts both forms, but comparing quoted headers with manifest
   values would make every successful upload look like a remote change."
  [etag]
  (when (string? etag)
    (let [etag (string/trim etag)
          etag (if (string/starts-with? etag "W/")
                 (subs etag 2)
                 etag)]
      (if (and (>= (count etag) 2)
               (= \" (first etag))
               (= \" (last etag)))
        (subs etag 1 (dec (count etag)))
        etag))))

(defn- assert-ok!
  [response]
  (if (.-ok response)
    response
    (p/then (.text response)
            (fn [body]
              (let [body-data (try
                                (js->clj (js/JSON.parse body) :keywordize-keys true)
                                (catch :default _ nil))
                    latest-etag (or (:latestEtag body-data)
                                    (get-in body-data [:stat :etag])
                                    (get-in body-data [:conflict :latestEtag]))
                    error (js/Error. (str "Journal API failed: " (.-status response) " " body))]
                (set! (.-status error) (.-status response))
                (set! (.-conflict? error) (= 412 (.-status response)))
                (set! (.-latestEtag error) latest-etag)
                (set! (.-responseBody error) body-data)
                (throw error))))))

(defn conditional-put-headers
  "Builds a conditional PUT from the last observed remote revision.

  Existing files must match their observed ETag; files without an observed
  revision may only be created when they are still absent remotely."
  [base-remote-etag]
  (cond-> {"content-type" "text/plain; charset=utf-8"}
    base-remote-etag (assoc "If-Match" base-remote-etag)
    (nil? base-remote-etag) (assoc "If-None-Match" "*")))

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
            :etag (or (normalize-etag (.get (.-headers response) "etag"))
                      (normalize-etag etag))}
           (p/let [response (assert-ok! response)
                   content (.text response)]
             {:status (.-status response)
              :path rpath
              :etag (normalize-etag (.get (.-headers response) "etag"))
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
        (p/let [headers (conditional-put-headers baseRemoteEtag)
                response (js/fetch (api-url "/api/graph/file" rpath)
                                   (clj->js {:method "PUT"
                                             :headers headers
                                             :body content}))
                response (assert-ok! response)]
          {:status (.-status response)
           :path rpath
           :etag (normalize-etag (.get (.-headers response) "etag"))}))
      (p/rejected (js/Error. (str "Invalid Journal file path: " (pr-str rpath)))))))
