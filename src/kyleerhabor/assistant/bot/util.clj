(ns kyleerhabor.assistant.bot.util)

(defn user [m]
  (:user (or (:member m) m)))
