(ns kyleerhabor.assistant.bot.util
  (:require [discljord.cdn :as cdn]))

(defn user [m]
  (:user (or (:member m) m)))
