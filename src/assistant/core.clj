(ns assistant.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [assistant.events :refer [handler]]
            [discljord.connections :refer [connect-bot!]]
            [discljord.messaging :refer [start-connection! stop-connection!]]
            [discljord.events :refer [message-pump!]]))

(def config (edn/read-string (slurp "config.edn")))

(defn -main []
  (let [event-ch (async/chan 128)
        conn-ch (connect-bot! (:token config) event-ch
                              :intents #{})
        msg-ch (start-connection! (:token config))]
    (message-pump! event-ch handler)
    (stop-connection! msg-ch)
    (async/close! event-ch)))

