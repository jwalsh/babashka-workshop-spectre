(ns spectre.core-test
  (:refer-clojure :exclude [derive])
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [spectre.core :as spectre]))

(deftest derive
  (testing "a passoword derivation"
    (is (= "HuqoBoquSeyn1'"
           (spectre/password "John Doe"
                             "hunter2"
                             "example.com"
                             {:variant :password :template :long})))))

(def ^:private john
  "John Doe's :password master key, made once: scrypt is the slow step."
  (delay (spectre/master-key "John Doe" "hunter2")))

(defn- derived
  "John Doe's password for site, example.com unless given, with opts."
  ([opts] (derived "example.com" opts))
  ([site opts] (spectre/derive @john site opts)))

(defn- fits?
  "Whether result is as long as pattern, each character one its letter allows."
  [pattern result]
  (and (= (count pattern) (count result))
       (every? (fn [[letter character]]
                 (str/includes? (spectre/char-classes letter) (str character)))
               (map vector pattern result))))

(deftest inputs-test
  (testing "the same inputs always give the same password"
    (is (= "HuqoBoquSeyn1'" (derived {}) (derived {}))))
  (testing "another site gives another password"
    (is (not= (derived {}) (derived "example.org" {}))))
  (testing "another counter gives another password"
    (is (not= (derived {}) (derived {:counter 2}))))
  (testing "each variant gives another, from a master key of its own"
    (is (apply distinct?
               (for [variant (keys spectre/scope)]
                 (spectre/password "John Doe" "hunter2" "example.com" {:variant variant}))))))

(deftest template-test
  (testing "a template decides the length and what each character may be"
    (doseq [[template patterns] spectre/templates
            :let [result (derived {:template template})]]
      (is (some #(fits? % result) patterns)
          (str template " gave " (pr-str result))))))
