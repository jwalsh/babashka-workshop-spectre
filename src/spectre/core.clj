(ns spectre.core
  "Spectre v3 stateless password derivation."
  (:refer-clojure :exclude [derive])
  (:require
   [clojure.string :as str]
   [spectre.scrypt :as scrypt]
   #_[spectre.scrypt-ffi :as scrypt])
  (:import
   [java.io ByteArrayOutputStream]
   [java.nio ByteBuffer]
   [javax.crypto Mac]
   [javax.crypto.spec SecretKeySpec]))

(def scope
  {:password "com.lyndir.masterpassword"
   :login "com.lyndir.masterpassword.login"
   :answer "com.lyndir.masterpassword.answer"})

(def templates
  {:maximum ["anoxxxxxxxxxxxxxxxxx" "axxxxxxxxxxxxxxxxxno"]
   :long ["CvcvnoCvcvCvcv" "CvcvCvcvnoCvcv" "CvcvCvcvCvcvno"
          "CvccnoCvcvCvcv" "CvccCvcvnoCvcv" "CvccCvcvCvcvno"
          "CvcvnoCvccCvcv" "CvcvCvccnoCvcv" "CvcvCvccCvcvno"
          "CvcvnoCvcvCvcc" "CvcvCvcvnoCvcc" "CvcvCvcvCvccno"
          "CvccnoCvccCvcv" "CvccCvccnoCvcv" "CvccCvccCvcvno"
          "CvcvnoCvccCvcc" "CvcvCvccnoCvcc" "CvcvCvccCvccno"
          "CvccnoCvcvCvcc" "CvccCvcvnoCvcc" "CvccCvcvCvccno"]
   :medium ["CvcnoCvc" "CvcCvcno"]
   :basic ["aaanaaan" "aannaaan" "aaannaaa"]
   :short ["Cvcn"]
   :pin ["nnnn"]
   :name ["cvccvcvcv"]
   :phrase ["cvcc cvc cvccvcv cvc" "cvc cvccvcvcv cvcv" "cv cvccv cvc cvcvccv"]})

(def char-classes
  {\V "AEIOU"
   \C "BCDFGHJKLMNPQRSTVWXYZ"
   \v "aeiou"
   \c "bcdfghjklmnpqrstvwxyz"
   \A "AEIOUBCDFGHJKLMNPQRSTVWXYZ"
   \a "AEIOUaeiouBCDFGHJKLMNPQRSTVWXYZbcdfghjklmnpqrstvwxyz"
   \n "0123456789"
   \o "@&%?,=[]_:-+*$#!'^~;()/."
   \x "AEIOUaeiouBCDFGHJKLMNPQRSTVWXYZbcdfghjklmnpqrstvwxyz0123456789!@#$%^&*()"
   \space " "})

(defn- u32
  "Four-byte big-endian unsigned int."
  [n]
  (.array (.putInt (ByteBuffer/allocate 4) (int n))))

(defn- utf8
  [s]
  (.getBytes ^String s "UTF-8"))

(defn- cat-bytes ^bytes [& arrs]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes a arrs] (.write out a 0 (alength a)))
    (.toByteArray out)))

(defn- ub
  "Byte as unsigned 0-255."
  [b i]
  (bit-and (aget b i) 0xff))

(defn master-key
  "Derive the 64-byte master key from full name and master password."
  ([full-name master-password]
   (master-key full-name master-password :password))
  ([full-name master-password variant]
   (let [name-bytes (utf8 full-name)
         salt (cat-bytes (utf8 (scope variant)) (u32 (alength name-bytes)) name-bytes)]
     ;; NOTE: Argon2 is the more modern recommended KDF and you can try that out as Spectre v4!
     (scrypt/scrypt (utf8 master-password) salt 32768 8 2 64))))

(defn- site-seed
  [mkey site counter variant]
  (let [site-bytes (utf8 site)
        msg (cat-bytes (utf8 (scope variant)) (u32 (alength site-bytes)) site-bytes (u32 counter))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. mkey "HmacSHA256")))]
    (.doFinal mac msg)))

(defn derive
  "Derive a password for a site from the master key.
   variant: :password :login :answer. tmpl: a template class keyword."
  ([mkey site]
   (derive mkey site {}))
  ([mkey site {:keys [counter variant template]
               :or {counter 1 variant :password template :long}}]
   (let [seed (site-seed mkey site counter variant)
         tset (templates template)
         tmpl (nth tset (mod (ub seed 0) (count tset)))]
     (str/join
      (map-indexed
       (fn [i ch]
         (let [cs (char-classes ch)]
           (nth cs (mod (ub seed (inc i)) (count cs)))))
       tmpl)))))

(defn password
  "Convenience: derive a site password directly from name and master password."
  [full-name master-password site opts]
  (derive (master-key full-name master-password (:variant opts :password)) site opts))

(comment

  (master-key "Alice" "a b c d" :password)
  (alength (master-key "Alice" "a b c d" :password))
  (take 8 (master-key "Alice" "a b c d" :password))
  (take 8 (master-key "Alice" "a b c d" :login))
  (derive (master-key "Alice" "a b c d" :password) "a.example.com" {:variant :password :template :long})
  (derive (master-key "Alice" "a b c d") "a.example.com")

  (password "Alice" "a b c d" "a.example.com" {:variant :password :template :long})
  (password "Bob" "b c d e" "b.example.com" {:variant :password :template :medium})
  (password "Charlie" "c d e" "c.example.com" {:variant :password :template :short})
  
  (def my-pass (password "John Doe"
                         "correct horse battery staple"
                         "example.com"
                         {:variant :password :template :maximum}))

  (assert (= "b0+aejObRu&7LB&Y#j%h" my-pass)))
