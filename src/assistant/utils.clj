(ns assistant.utils
  (:require [clojure.string :as str]))

(defn hex->int [hex]
  (Long/parseLong (if (str/starts-with? hex "#")
                    (subs hex 1)
                    hex) 16))

(defmacro ignore-ex
  "Evaluates `body`, returning the value of the last expression or `nil` if an exception is caught."
  [& body]
  `(try ~@body
     (catch Exception _#)))

(defn precision
  "Round a double to the given precision (number of significant digits). Borrowed from
  https://stackoverflow.com/a/25098576/14695788 (modified)."
  [n precision]
  (let [factor (Math/pow 10 precision)
        n (* n factor)]
    (/ (if (pos? n)
         (Math/floor n)
         (Math/ceil n)) factor)))

(defn rpartial
  "Like `partial`, but applies the args called with `f` before the partial args (`pargs`)."
  [f & pargs] ; p[artial] args
  (fn [& rargs] ; r[eal] args
    (apply f (concat rargs pargs))))

(defn split-keys
  "Splits map `m` into two maps containing keys from (first) and not from (second) `ks`."
  [m ks]
  [(select-keys m ks)
   (apply dissoc m ks)])

(defn truncate
  "Limits string `s` to `lim` number of characters, appending `trunc` to indicate truncation."
  ([s lim] (truncate s lim "..."))
  ([s lim trunc]
   (if (< lim (count s))
     (str (subs s 0 (- lim (count trunc))) trunc)
     s)))