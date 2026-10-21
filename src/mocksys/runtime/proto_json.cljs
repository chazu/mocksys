(ns mocksys.runtime.proto-json
  "A reply body (parsed JSON) checked against its protobufjs message type and
  converted to the object protobufjs encodes, instead of letting protobufjs coerce
  it (\"false\" to true, \"abc\" to 0, an unknown enum name to the default).

  A field is named as in the proto or by its proto3 JSON name (lowerCamelCase).
  Values take the proto3 JSON forms: a 64-bit integer as a number or a decimal
  string, a 32-bit one also as a decimal string, a float as a number, a numeric
  string, NaN or Infinity, bytes as base64, an enum by name or number, a
  google.protobuf.Timestamp as an RFC 3339 string or {seconds, nanos}, and null as
  unset. Anything else is a problem that names the field. A key that names no field
  is collected as unknown, which is not a problem."
  (:require [clojure.string :as str]
            ["protobufjs" :as protobuf]))

(defn json-name
  "The proto3 JSON name of a field: display_name is displayName."
  [proto-name]
  (str/replace proto-name #"_(.)" #(str/upper-case (second %))))

(def ^:private int32-range
  {"int32" [-2147483648 2147483647] "sint32" [-2147483648 2147483647] "sfixed32" [-2147483648 2147483647]
   "uint32" [0 4294967295] "fixed32" [0 4294967295]})

(def ^:private int64-types #{"int64" "sint64" "sfixed64" "uint64" "fixed64"})
(def ^:private unsigned #{"uint64" "fixed64" "uint32" "fixed32"})

(defn- shown [v]
  (cond
    (nil? v)     "null"
    (array? v)   "an array"
    (string? v)  (let [s (js/JSON.stringify v)]
                   (str "the string " (if (> (count s) 40) (str (subs s 0 37) "...\"") s)))
    (number? v)  (str "the number " v)
    (boolean? v) (str v)
    :else        "an object"))

(defn- problem!
  "Record that the value at `path` is not `wanted`; returns nil."
  [ctx path wanted v]
  (swap! (:problems ctx) conj (str path ": expected " wanted ", got " (shown v)))
  nil)

(defn- integer-text? [v] (and (string? v) (re-matches #"-?\d+" v)))

(defn- scalar [ctx path t v]
  (let [bad #(problem! ctx path % v)]
    (cond
      (= "string" t) (if (string? v) v (bad "a string"))
      (= "bool" t)   (if (boolean? v) v (bad "a bool"))
      (= "bytes" t)  (if (and (string? v) (re-matches #"[A-Za-z0-9+/_-]*={0,2}" v)) v (bad "base64 bytes"))

      (#{"double" "float"} t)
      (cond (number? v) v
            (#{"NaN" "Infinity" "-Infinity"} v) v
            (and (string? v) (not (str/blank? v)) (js/isFinite (js/Number v))) (js/Number v)
            :else (bad "a number"))

      (int64-types t)
      (let [s (cond (and (number? v) (js/Number.isSafeInteger v)) (str v)
                    (integer-text? v) v)]
        (cond (nil? s)                                        (bad "an integer, or a decimal string")
              (and (unsigned t) (str/starts-with? s "-"))     (bad "a non-negative integer")
              :else                                           s))

      :else
      (let [[lo hi] (int32-range t)
            n       (cond (number? v) v (integer-text? v) (js/Number v))]
        (cond (not (and n (js/Number.isInteger n))) (bad "an integer")
              (not (<= lo n hi))                    (bad (str "an integer from " lo " to " hi))
              :else                                 n)))))

(defn- enum-value [ctx path ^js e v]
  (let [names (js-keys (.-values e))]
    (if (or (and (string? v) (some #{v} names)) (and (number? v) (js/Number.isInteger v)))
      v
      (problem! ctx path (str "one of " (str/join ", " names)) v))))

(defn- timestamp [ctx path s]
  (let [[_ base frac zone] (re-matches #"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})" s)
        ms (when base (js/Date.parse (str base zone)))]
    (if (and ms (not (js/isNaN ms)))
      #js {:seconds (str (js/Math.floor (/ ms 1000)))
           :nanos   (js/parseInt (subs (str frac "000000000") 0 9) 10)}
      (problem! ctx path "an RFC 3339 timestamp or {seconds, nanos}" s))))

(declare message)

(defn- single [ctx path ^js f v]
  (let [rt (.-resolvedType f)]
    (cond
      (nil? v)                       (problem! ctx path "a value" v)
      (instance? protobuf/Type rt)   (message ctx path rt v)
      (instance? protobuf/Enum rt)   (enum-value ctx path rt v)
      :else                          (scalar ctx path (.-type f) v))))

(defn- map-key-ok? [key-type k]
  (cond (= "bool" key-type)           (#{"true" "false"} k)
        (= "string" key-type)         true
        (unsigned key-type)           (re-matches #"\d+" k)
        :else                         (integer-text? k)))

(defn- field-value [ctx path ^js f v]
  (cond
    (.-map f)
    (if-not (and (object? v) (not (array? v)))
      (problem! ctx path "an object (a map)" v)
      (let [out #js {}]
        (doseq [k (js-keys v)]
          (if (map-key-ok? (.-keyType f) k)
            (aset out k (single ctx (str path "." k) f (aget v k)))
            (problem! ctx (str path "." k) (str "a " (.-keyType f) " map key") k)))
        out))

    (.-repeated f)
    (if (array? v)
      (into-array (map-indexed (fn [i x] (single ctx (str path "[" i "]") f x)) v))
      (problem! ctx path "an array" v))

    :else (single ctx path f v)))

(defn- message [ctx path ^js t v]
  (let [at #(if (str/blank? path) % (str path "." %))]
    (cond
      (and (string? v) (= ".google.protobuf.Timestamp" (.-fullName t)))
      (timestamp ctx path v)

      (not (and (object? v) (not (array? v))))
      (problem! ctx path "an object" v)

      :else
      (let [fields (array-seq (.-fieldsArray t))
            names  (set (mapcat (fn [^js f] [(.-name f) (json-name (.-name f))]) fields))
            out    #js {}]
        (doseq [k (js-keys v) :when (not (names k))]
          (swap! (:unknown ctx) conj (at k)))
        (doseq [^js f fields
                :let [n (.-name f)
                      x (if (js-in n v) (aget v n) (aget v (json-name n)))]
                :when (some? x)]
          (aset out n (field-value ctx (at n) f x)))
        (doseq [^js o (array-seq (.-oneofsArray t))
                :let [members (filter #(js-in % out) (array-seq (.-oneof o)))]
                :when (< 1 (count members))]
          (swap! (:problems ctx) conj (str (at (str/join ", " members)) ": only one member of oneof "
                                           (.-name o) " may be set")))
        out))))

(defn encode
  "Check `doc` (parsed JSON) against the message type `t`. Returns {:value the
   object to encode, :problems [\"path: expected X, got Y\"], :unknown [paths of keys
   no field declares]}; :value is only good when :problems is empty."
  [^js t doc]
  (let [ctx {:problems (atom []) :unknown (atom [])}
        v   (message ctx "" t doc)]
    {:value v :problems @(:problems ctx) :unknown @(:unknown ctx)}))
