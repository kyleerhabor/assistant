(ns kyleerhabor.assistant.bot.event
  (:require
   [clojure.core.async :as async :refer [<! go]]
   [clojure.java.io :as io]
   [kyleerhabor.assistant.bot.command :as cmd]
   [kyleerhabor.assistant.effect :as eff]
   [discljord.messaging :as msg]))

(defn stream [opts respond]
  (go
    (with-open [s (io/input-stream (:content (:stream opts)))]
      (<! (respond (assoc-in opts [:stream :content] s))))))

(declare effect)

(defn handle [handler res conn]
  (async/take! res #(effect conn (handler %))))

(defn effect [conn data]
  ;; TODO: Handle :file respond field
  (eff/process data {:bulk-delete-messages (fn [{:keys [channel-id msg-ids opts handler]}]
                                             (let [res (msg/bulk-delete-messages! conn channel-id msg-ids opts)]
                                               (some-> handler (handle res conn))))
                     :create-followup-message (fn [{:keys [app-id token opts handler]}]
                                                (let [respond (partial msg/create-followup-message! conn app-id token)
                                                      res (if (:stream opts)
                                                            (stream opts respond)
                                                            (respond opts))]
                                                  (some-> handler (handle res conn))))
                     :create-interaction-response (fn [{:keys [id token type opts handler]}]
                                                    (let [respond (partial msg/create-interaction-response! conn id token type)
                                                          res (if (:stream opts)
                                                                (stream opts respond)
                                                                (respond opts))]
                                                      (some-> handler (handle res conn))))
                     :delete-message (fn [{:keys [channel-id message-id opts handler]}]
                                       (let [res (msg/delete-message! conn channel-id message-id opts)]
                                         (some-> handler (handle res conn))))
                     :get-channel-messages (fn [{:keys [channel-id opts handler]}]
                                             (let [res (msg/get-channel-messages! conn channel-id opts)]
                                               (some-> handler (handle res conn))))}))

(defn interaction-create [_ inter conn]
  (if-let [route (cmd/route inter)]
    (let [cmd (get-in cmd/commands (interpose :options (:path route)))
          handler (:handler cmd)]
      ;; We could go higher, but I can't be bothered to for now.
      (effect conn (handler {:interaction inter})))))

(def handlers {:interaction-create [interaction-create]})
