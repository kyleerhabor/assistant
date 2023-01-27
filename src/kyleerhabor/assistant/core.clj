(ns kyleerhabor.assistant.core
  (:require
   [kyleerhabor.assistant.bot]
   [mount.core :as m]))

(defn run []
  (m/start))

(defn stop []
  (m/stop))

(defn system [start stop]
  (start)
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop)))

(defn -main []
  (system run
    (fn []
      (stop)
      (shutdown-agents))))

(comment
  ;; Run
  (run)
  
  ;; Stop
  (stop))
