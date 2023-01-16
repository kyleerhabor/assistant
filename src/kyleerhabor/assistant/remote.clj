(ns kyleerhabor.assistant.remote
  (:require
   [kyleerhabor.assistant.config :refer [config]]
   [kyleerhabor.assistant.util :refer [handle-chan read-transit write-transit]]
   [hato.client :as hc]
   [async-error.core :refer [go-try <?]])
  (:import (java.time Duration)))

(def default-timeout (.toMillis (Duration/ofSeconds 30)))

(defn async [req] ; Better name?
  (handle-chan (partial hc/request (assoc req :async? true))))

(defn query [q]
  {:accept :transit+json
   :content-type :transit+json
   :body (str (write-transit q :json))})

(def hue-client (hc/build-http-client {:connect-timeout (or (::timeout config) default-timeout)}))

(defn hue-request [req]
  (assoc req
    :url (::hue config)
    :request-method :post
    :http-client hue-client))

(defn hue
  ([q]
   (go-try
     (let [req (assoc (hue-request (query q)) :as :byte-array)
           res (<? (async req))]
       (read-transit (:body res) :json)))))
