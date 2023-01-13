(ns kyleerhabor.assistant.bot
  (:require
   [clojure.core.async :as async :refer [<! <!!]]
   [kyleerhabor.assistant.bot.event :as event]
   [kyleerhabor.assistant.bot.command :as cmd]
   [kyleerhabor.assistant.config :refer [config]]
   [kyleerhabor.assistant.util :refer [empty-set]]
   [discljord.connections :as conn]
   [discljord.events :as evt]
   [discljord.messaging :as msg]
   [mount.core :as m :refer [defstate]]
   [clojure.tools.logging :as log]))

(def default-event-capacity 64)
(def default-compression true)

(defn connect
  ([token event-ch] (connect token event-ch nil))
  ([token event-ch & {:keys [intents compression?]
                      :or {intents empty-set
                           ;; Maybe just "compress?"
                           compression? default-compression}}]
   (let [conn-ch (conn/connect-bot! token event-ch
                   :intents intents
                   :disable-compression (not compression?))
         msg-ch (msg/start-connection! token)]
     {:connection conn-ch
      :messaging msg-ch})))

(defstate event>
  :start (async/chan (async/dropping-buffer (or (::event-capacity config) default-event-capacity)))
  :stop (async/close! event>))

(defn process
  "Continuously takes from `chan` and calls `f` with its value until the channel is closed."
  [chan f]
  (async/go-loop []
    ;; Much better than using a :disconnect event (where the buffer may not process it) or a separate "kill" channel
    ;; (two things to coordinate, plus performance cost)
    (when-let [v (<! chan)]
      (f v)
      (recur))))

(defstate conn
  :start (connect (::token config) event>
           :compression? (or (::compression? config) default-compression))
  :stop (do
          (msg/stop-connection! (:messaging conn))
          (conn/disconnect-bot! (:connection conn))))

(defstate events>
  "Processes events."
  :start (process event>
           (fn [[type data]]
             (try (evt/dispatch-handlers event/handlers type data (:messaging conn))
               (catch Exception ex
                 (log/error ex "Error processing event" type data)))))
  ;; Note that this isn't to stop the loop, but to formally say that the channel is closed.
  :stop (async/close! events>))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn upload-commands [_]
  (m/start-without #'events>)
  (log/info "Upload:" (<!! (cmd/upload (:messaging conn))))
  (m/stop))
