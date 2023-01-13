(ns kyleerhabor.assistant.bot.event
  (:require
   [clojure.core.async :refer [<! go]]
   [clojure.java.io :as io]
   [kyleerhabor.assistant.bot.command :as cmd]
   [kyleerhabor.assistant.effect :as eff]
   [discljord.messaging :as msg]))

(defn stream [opts respond]
  (go
    (with-open [s (io/input-stream (:content (:stream opts)))]
      (<! (respond (assoc-in opts [:stream :content] s))))))

(defn effect [conn data]
  ;; TODO: Handle :file respond field
  (eff/process data {:create-interaction-response (fn [{:keys [id token type opts handler]}]
                                                    (let [respond (partial msg/create-interaction-response! conn id token type)
                                                          res (if (:stream opts)
                                                                (stream opts respond)
                                                                (respond opts))]
                                                      (if handler
                                                        (go (effect conn (handler (<! res)))))))
                     :create-followup-message (fn [{:keys [app-id token opts handler]}]
                                                (let [respond (partial msg/create-followup-message! conn app-id token)
                                                      res (if (:stream opts)
                                                            (stream opts respond)
                                                            (respond opts))]
                                                  (if handler
                                                    (go (effect conn (handler (<! res)))))))}))

(defn interaction-create [_ inter conn]
  ;; Would be better to return the associated command rather than just the handler.
  (if-let [handler (cmd/handler inter)]
    ;; We could go higher, but I can't be bothered to for now.
    (effect conn (handler inter))))

(def handlers {:interaction-create [interaction-create]})
