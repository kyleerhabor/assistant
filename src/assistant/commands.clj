(ns assistant.commands 
  (:require [assistant.middleware :as middleware]
            [assistant.commands.core :as core]))

(def commands {:exit (-> core/exit
                         middleware/sysop)})

(def commands {:exit {:command core/exit
                      :middleware [middleware/sysop]}})
