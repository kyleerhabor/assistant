(ns kyleerhabor.assistant.remote
  (:require
   [clojure.core.async :refer [go <!]]
   [kyleerhabor.assistant.config :refer [config]]
   [kyleerhabor.assistant.util :refer [ex? handle-chan read-transit write-transit]]
   [com.rpl.specter :as sp]
   [hato.client :as hc])
  (:import (java.time Duration)))

(defn async [req] ; Maybe a better name?
  (handle-chan (partial hc/request (assoc req :async? true))))

(defn query [body]
  {:accept :transit+json
   :content-type :transit+json
   :body body})

(def default-timeout (.toMillis (Duration/ofSeconds 30)))

(def hue-client (hc/build-http-client {:connect-timeout (or (::timeout config) default-timeout)}))

(defn hue-request [req]
  (assoc req
    :url (::hue config)
    :request-method :post
    :http-client hue-client))

(defn hue [q]
  (go
    (let [req (assoc (hue-request (query (str (write-transit q :json))))
                :as :byte-array)
          res (<! (async req))]
      (sp/transform ex? #(read-transit (:body %) :json) res))))
