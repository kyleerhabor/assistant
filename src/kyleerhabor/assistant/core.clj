(ns kyleerhabor.assistant.core
  (:require
   [kyleerhabor.assistant.bot]
   [mount.core :as m]))

(defn -main []
  (m/start))
