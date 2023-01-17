(ns kyleerhabor.assistant.util
  (:require
   [clojure.core.async :as async]
   [cognitect.transit :as t])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

(def empty-set #{})

(defn handle-chan
  "Takes `f`, a two-arity function representing ok and error handlers, and returns a core.async channel that'll be put
  the single arity value from the handlers (e.g. an HTTP request accepting a response and error handler)."
  [f]
  (let [chan (async/chan)
        respond (fn [val] (async/put! chan val))]
    (f respond respond)
    chan))

(defn read-transit
  ([bytes type] (read-transit bytes type nil))
  ([bytes type opts]
   (t/read (t/reader (ByteArrayInputStream. bytes) type opts))))

(defn write-transit
  ([val type] (write-transit val type nil))
  ([val type opts]
   (let [out (ByteArrayOutputStream.)]
     (t/write (t/writer out type opts) val)
     out)))

(defn ex? [x]
  (instance? Exception x))

(defn given
  "Tests if `x` passes predicate `pred`, calling `then` if so with `x`, else returning `x`."
  [x pred then]
  (if (pred x)
    (then x)
    x))
