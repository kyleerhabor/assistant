(ns kyleerhabor.assistant.bot.event
  (:require [kyleerhabor.assistant.bot.command :as cmd]))

(defn interaction-create [_ inter conn]
  (if-let [handle (cmd/handler inter)]
    (handle (cmd/interact conn inter))))

(def handlers {:interaction-create [interaction-create]})
