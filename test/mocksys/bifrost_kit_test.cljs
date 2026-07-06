(ns mocksys.bifrost-kit-test
  "Integration: the bifrost kit composes an OpenAI-compatible chat-completions
   surface over a seeded virtual_keys collection, validates cleanly, and lowers to
   an imposter. Then drive the chat op through the harness — VK auth (find in the
   live collection), model echo, canned OpenAI-shaped completion, and the 401 paths."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [mocksys.harness :as h]
            [mocksys.kits :as kits]
            [mocksys.contract :as contract]
            [mocksys.effect :as effect]))

(def ^:private bifrost (kits/bifrost "bifrost/twin" {}))

(defn- op [id]
  (some #(when (= id (get % "id")) %) (get bifrost "operations")))

;; resolve each collection's seed *name* to its array, exactly as `lower` does, so
;; the harness sees the kit's real seed data.
(defn- resolved-colls []
  (let [seed (get bifrost "seed")]
    (into {} (for [[n spec] (get-in bifrost ["state" "collections"])]
               [n {"idField" (get spec "idField") "seed" (get seed (get spec "seed"))}]))))

(def ^:private live-vk "sk-bf-live-0000000000")

(deftest bifrost-validates-and-lowers
  (testing "the composed contract has no validation errors"
    (is (empty? (:errors (contract/validate bifrost)))))
  (testing "it lowers to an imposter without throwing"
    (is (seq (get (contract/lower bifrost) "stubs"))))
  (testing "the chat-completions op is wired in"
    (is (some? (op "chat-completions")))))

(deftest chat-completion-happy-path
  (let [colls (resolved-colls)
        r (h/run (op "chat-completions")
                 {:path "/v1/chat/completions"
                  :headers {:x-bf-vk live-vk}
                  :body {"model" "gpt-4o-mini"
                         "messages" [{"role" "user" "content" "hi"}]}}
                 {:collections colls} (h/fresh-state))]
    (testing "authenticated VK returns 200 with an OpenAI-shaped completion"
      (is (= 200 (:status r)))
      (is (= "chat.completion" (get-in r [:json "object"])))
      (is (str/starts-with? (get-in r [:json "id"]) "chatcmpl-")))
    (testing "the requested model is echoed back"
      (is (= "gpt-4o-mini" (get-in r [:json "model"]))))
    (testing "usage carries integer token counts (literals, not strings)"
      (is (number? (get-in r [:json "usage" "total_tokens"])))
      (is (= 21 (get-in r [:json "usage" "total_tokens"]))))
    (testing "the assistant message + finish_reason are present"
      (is (= "assistant" (get-in r [:json "choices" 0 "message" "role"])))
      (is (string? (get-in r [:json "choices" 0 "message" "content"])))
      (is (= "stop" (get-in r [:json "choices" 0 "finish_reason"]))))
    (testing "the Bifrost extra_fields envelope names the serving provider"
      (is (some? (get-in r [:json "extra_fields" "provider"]))))))

(deftest chat-completion-rejects-missing-vk
  (testing "no x-bf-vk header → 401 with an error envelope"
    (let [r (h/run (op "chat-completions")
                   {:path "/v1/chat/completions"
                    :body {"model" "gpt-4o-mini" "messages" []}}
                   {:collections (resolved-colls)} (h/fresh-state))]
      (is (= 401 (:status r)))
      (is (some? (get-in r [:json "error" "message"]))))))

(deftest chat-completion-rejects-unknown-and-inactive-vk
  (let [colls (resolved-colls)]
    (testing "an unknown VK value → 401"
      (is (= 401 (:status (h/run (op "chat-completions")
                                 {:path "/v1/chat/completions"
                                  :headers {:x-bf-vk "sk-bf-does-not-exist"}
                                  :body {"model" "gpt-4o-mini"}}
                                 {:collections colls} (h/fresh-state))))))
    (testing "a seeded but inactive VK → 401 (status gate)"
      (is (= 401 (:status (h/run (op "chat-completions")
                                 {:path "/v1/chat/completions"
                                  :headers {:x-bf-vk "sk-bf-off-00000000000"}
                                  :body {"model" "gpt-4o-mini"}}
                                 {:collections colls} (h/fresh-state))))))))

;; --- Slice 2: control-plane CRUD (virtual-keys + providers) ---

(deftest control-plane-ops-are-wired
  (testing "the virtual_keys + providers CRUD ops are composed in"
    (is (every? some? (map op ["create-virtual_keys" "list-virtual_keys" "get-virtual_keys"
                               "update-virtual_keys" "delete-virtual_keys"
                               "create-providers" "list-providers"])))))

(deftest create-a-virtual-key-then-authenticate-with-it
  (let [colls (resolved-colls)
        state (h/fresh-state)
        created (h/run (op "create-virtual_keys")
                       {:path "/api/governance/virtual-keys"
                        :body {"value" "sk-bf-fresh-9999" "name" "fresh key"}}
                       {:collections colls} state)
        vkid (get-in created [:json "id"])]
    (testing "create mints a vk_ id and defaults status to active"
      (is (= 201 (:status created)))
      (is (str/starts-with? vkid "vk_"))
      (is (= "active" (get-in created [:json "status"]))))
    (testing "the freshly-created key immediately authenticates a chat call (shared state)"
      (is (= 200 (:status (h/run (op "chat-completions")
                                 {:path "/v1/chat/completions"
                                  :headers {:x-bf-vk "sk-bf-fresh-9999"}
                                  :body {"model" "gpt-4o"}}
                                 {:collections colls} state)))))
    (testing "listing returns the seeded keys plus the newly created one"
      (let [l (h/run (op "list-virtual_keys") {:path "/api/governance/virtual-keys"}
                     {:collections colls} state)]
        (is (<= 3 (count (:json l))))))
    (testing "get by id reads the created key back"
      (let [g (h/run (op "get-virtual_keys") {:path (str "/api/governance/virtual-keys/" vkid)}
                     {:collections colls} state)]
        (is (= 200 (:status g)))
        (is (= "sk-bf-fresh-9999" (get-in g [:json "value"])))))
    (testing "get on a missing id → bifrost-shaped 404"
      (let [g (h/run (op "get-virtual_keys") {:path "/api/governance/virtual-keys/vk_nope"}
                     {:collections colls} state)]
        (is (= 404 (:status g)))
        (is (some? (get-in g [:json "error" "message"])))))))

(deftest providers-crud-round-trips-nested-keys
  (let [colls (resolved-colls)
        state (h/fresh-state)
        c (h/run (op "create-providers")
                 {:path "/api/providers"
                  :body {"provider" "openai"
                         "keys" [{"value" "sk-upstream" "models" ["gpt-4o"] "weight" 1.0}]}}
                 {:collections colls} state)]
    (testing "create a provider config, nested keys[] round-trip"
      (is (= 201 (:status c)))
      (is (= "openai" (get-in c [:json "provider"])))
      (is (= "gpt-4o" (get-in c [:json "keys" 0 "models" 0])))
      (is (number? (get-in c [:json "keys" 0 "weight"]))))
    (testing "list providers returns the created config"
      (is (seq (:json (h/run (op "list-providers") {:path "/api/providers"}
                             {:collections colls} state)))))))

;; --- Slice 3: per-VK rate limiting on inference ---

(def ^:private bifrost-rl (kits/bifrost "bifrost/rl" {:rate-limit {"max" 2 "window" 60}}))

(defn- rl-op [id] (some #(when (= id (get % "id")) %) (get bifrost-rl "operations")))

(defn- rl-colls []
  (let [seed (get bifrost-rl "seed")]
    (into {} (for [[n spec] (get-in bifrost-rl ["state" "collections"])]
               [n {"idField" (get spec "idField") "seed" (get seed (get spec "seed"))}]))))

(deftest chat-completions-rate-limited-per-vk
  (let [colls (rl-colls)
        state (h/fresh-state)
        call  #(h/run (rl-op "chat-completions")
                      {:path "/v1/chat/completions" :headers {:x-bf-vk live-vk}
                       :body {"model" "gpt-4o"}}
                      {:collections colls} state)]
    (testing "the twin is clock-using (limit forces the virtual clock on)"
      (is (effect/contract-uses-clock? bifrost-rl)))
    (testing "calls within budget pass and carry OpenAI-style rate-limit headers"
      (is (= 200 (:status (call))))
      (let [r2 (call)]
        (is (= 200 (:status r2)))
        (is (= "2" (get-in r2 [:headers "x-ratelimit-limit-requests"])))
        (is (= "0" (get-in r2 [:headers "x-ratelimit-remaining-requests"])))))
    (testing "the call past budget → 429 with a Bifrost error envelope + limit header"
      (let [r3 (call)]
        (is (= 429 (:status r3)))
        (is (some? (get-in r3 [:json "error" "message"])))
        (is (= "2" (get-in r3 [:headers "x-ratelimit-limit-requests"])))))))

(deftest bifrost-without-rate-limit-has-no-limit
  (testing "the default twin (no :rate-limit) never 429s and has no clock forced by limit"
    (let [colls (resolved-colls)
          state (h/fresh-state)
          hit   #(h/run (op "chat-completions")
                        {:path "/v1/chat/completions" :headers {:x-bf-vk live-vk}
                         :body {"model" "gpt-4o"}}
                        {:collections colls} state)]
      (is (every? #(= 200 %) (map (fn [_] (:status (hit))) (range 5)))))))

;; --- Slice 3 (§8.3): crud-collection rate-limit is parameterizable ---

(deftest crud-collection-rate-limit-key-source-and-miss
  (let [frag (kits/crud-collection
              {:coll "items" :base "/items" :seed-name "items"
               :rate-limit {"max" 1 "window" 60
                            "key-source" {"header" "x-bf-vk"}
                            "miss" {"status" 429 "json" {"error" {"message" "slow down"}}}
                            "headers" {"limit" "x-ratelimit-limit-requests"}}})
        get-op (some #(when (= "get-items" (get % "id")) %) (:operations frag))
        colls  {"items" {"idField" "id" "seed" [{"id" "a"}]}}
        state  (h/fresh-state)
        call   #(h/run get-op {:path "/items/a" :headers {:x-bf-vk "k1"}}
                       {:collections colls} state)]
    (testing "first call within budget passes (keyed on x-bf-vk, not the bearer header)"
      (is (= 200 (:status (call)))))
    (testing "over budget → the caller's custom miss body, not the hardcoded Okta 429"
      (let [r (call)]
        (is (= 429 (:status r)))
        (is (= "slow down" (get-in r [:json "error" "message"])))
        (is (nil? (get-in r [:json "errorCode"])))))))
