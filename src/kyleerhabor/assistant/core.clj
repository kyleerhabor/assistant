(ns kyleerhabor.assistant.core
  (:require
   [clojure.core.async :refer [<!!]]
   [kyleerhabor.assistant.bot :as bot]
   [mount.core :as m]))

(defn run []
  (m/start))

(defn stop []
  (m/stop))

(defn system [start stop]
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop))
  (start))

(defn -main []
  (system
    (fn []
      (run)
      ;; Block to finish processing events.
      (<!! bot/events>))
    (fn []
      (stop)
      (shutdown-agents))))

(comment
  ;; Run
  (run)

  ;; Stop
  (stop))
