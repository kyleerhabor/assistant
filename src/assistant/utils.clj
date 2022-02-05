(ns assistant.utils)

(defmacro ignore-ex
  "Evaluates `body`, returning the value of the last expression or `nil` if an exception is caught."
  [& body]
  `(try ~@body
     (catch Exception _#)))
