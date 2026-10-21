(ns mocksys.scim
  "SCIM filters (RFC 7644 §3.4.2.2) over rows of plain JS objects: comparisons
  `eq ne co sw ew gt ge lt le pr` on dotted field paths, `and` binding tighter than
  `or`, `not ( … )`, parentheses, and JSON literal values (quoted strings with
  escapes, numbers, true, false, null). Complex attribute filters are not supported.")

(defn dget
  "The value at dotted path `p` in `o`, walking JS properties; a missing link ends
   the walk with that nil or undefined."
  [o p]
  (reduce (fn [o k] (if (nil? o) (reduced o) (unchecked-get o k))) o (.split (js/String p) ".")))

(defn- fail [m]
  (throw (ex-info (str "Invalid filter: " m) {:scim true})))

(defn invalid?
  "True for the error `predicate` throws on a malformed filter."
  [e]
  (true? (:scim (ex-data e))))

(defn- space? [s i] (<= (.charCodeAt s i) 32))
(defn- delim? [c] (or (= c "(") (= c ")") (= c "\"")))

(defn- tokenizer
  "A fn returning the next token of `s` on each call: {:t :end}, {:t \"(\"} or
   {:t \")\"}, {:t :v :v value} for a quoted string, or {:t :w :v word}. Tokens are
   read one at a time, so a parse error ahead of a bad token is the one reported."
  [s]
  (let [n (.-length s) i (volatile! 0)]
    (fn []
      (while (and (< @i n) (space? s @i)) (vswap! i inc))
      (if (>= @i n)
        {:t :end}
        (let [c (aget s @i)]
          (cond
            (or (= c "(") (= c ")"))
            (do (vswap! i inc) {:t c})

            (= c "\"")
            (let [k (loop [k (inc @i)]
                      (cond (>= k n) k
                            (= "\"" (aget s k)) k
                            (= 92 (.charCodeAt s k)) (recur (+ k 2))
                            :else (recur (inc k))))
                  _   (when (>= k n) (fail "unterminated string"))
                  raw (.slice s @i (inc k))]
              (vreset! i (inc k))
              {:t :v :v (try (js/JSON.parse raw) (catch :default _ (fail (str "bad string " raw))))})

            :else
            (let [start @i]
              (while (and (< @i n) (not (space? s @i)) (not (delim? (aget s @i)))) (vswap! i inc))
              {:t :w :v (.slice s start @i)})))))))

(defn- eqv [v x]
  (cond (nil? x)    (nil? v)
        (string? x) (and (some? v) (identical? (js/String v) x))
        :else       (identical? v x)))

(defn- order [a b]
  (if (and (number? a) (number? b))
    (- a b)
    (compare (js/String a) (js/String b))))

(defn- literal [{:keys [t v]}]
  (case t
    :v v
    :w (case (.toLowerCase v)
         "true" true "false" false "null" nil
         (if (js/isNaN (js/Number v)) (fail "expected a value") (js/Number v)))
    (fail "expected a value")))

(def ^:private comparisons #{"eq" "ne" "co" "sw" "ew" "gt" "ge" "lt" "le"})

(defn- compare-with [op x]
  (fn [v]
    (case op
      "eq" (eqv v x)
      "ne" (not (eqv v x))
      (and (some? v) (some? x)
           (let [vs (js/String v) xs (js/String x)]
             (case op
               "co" (>= (.indexOf vs xs) 0)
               "sw" (zero? (.indexOf vs xs))
               "ew" (and (>= (.-length vs) (.-length xs)) (= (.slice vs (- (.-length vs) (.-length xs))) xs))
               (let [c (order v x)]
                 (case op "gt" (pos? c) "ge" (>= c 0) "lt" (neg? c) "le" (<= c 0)))))))))

(defn predicate
  "A fn of one row, true when the row satisfies the filter `expr`. Throws an error
   that `invalid?` recognizes when `expr` is malformed."
  [expr]
  (let [tok  (tokenizer (js/String expr))
        look (volatile! nil)
        peek (fn [] (or @look (vreset! look (tok))))
        next (fn [] (let [x (peek)] (vreset! look nil) x))
        kw?  (fn [w] (let [{:keys [t v]} (peek)] (and (= :w t) (= w (.toLowerCase v)))))
        close! (fn [] (when-not (= ")" (:t (next))) (fail "expected )")))]
    (letfn [(or-expr []
              (loop [a (and-expr)]
                (if (kw? "or")
                  (do (next) (recur (let [l a r (and-expr)] #(or (l %) (r %)))))
                  a)))
            (and-expr []
              (loop [a (unary)]
                (if (kw? "and")
                  (do (next) (recur (let [l a r (unary)] #(and (l %) (r %)))))
                  a)))
            (unary []
              (cond
                (kw? "not")
                (do (next)
                    (when-not (= "(" (:t (next))) (fail "expected ( after not"))
                    (let [f (or-expr)] (close!) #(not (f %))))

                (= "(" (:t (peek)))
                (do (next) (let [f (or-expr)] (close!) f))

                :else (comparison)))
            (comparison []
              (let [a (next)
                    _ (when-not (= :w (:t a)) (fail "expected an attribute"))
                    o (next)
                    _ (when-not (= :w (:t o)) (fail (str "expected an operator after " (:v a))))
                    field (:v a)
                    op    (.toLowerCase (:v o))]
                (cond
                  (= "pr" op) (fn [r] (let [v (dget r field)] (and (some? v) (not (identical? "" v)))))
                  (not (comparisons op)) (fail (str "unknown operator " (:v o)))
                  :else (let [test (compare-with op (literal (next)))]
                          (fn [r] (test (dget r field)))))))]
      (when (= :end (:t (peek))) (fail "empty filter"))
      (let [root (or-expr)
            {:keys [t v]} (peek)]
        (when-not (= :end t)
          (fail (str "unexpected " (if (js/Boolean v) v (if (keyword? t) (name t) t)))))
        root))))
