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
  "Variant to the string that scopes its keys: what a derived value is for.
   :password authenticates, :login names you, :answer is for a recovery
   question. The string goes into the master key's salt and the site seed."
  {:password "com.lyndir.masterpassword"
   :login "com.lyndir.masterpassword.login"
   :answer "com.lyndir.masterpassword.answer"})

(def templates
  "Template to its patterns, one letter for each character of the result.
   `char-classes` says what a letter may become."
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
  "Pattern letter to the characters it may become."
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
  "n as four bytes, most significant first: how a length and the counter go
   into a salt or a seed. n is taken as a JVM int, so it is below 2^31.
   (seq (u32 258)) is (0 0 1 2)."
  [n]
  (.array (.putInt (ByteBuffer/allocate 4) (int n))))

(defn- utf8
  "s as its UTF-8 bytes. The lengths the algorithm writes are counts of
   these bytes, not of characters, and a JVM byte is signed.
   (seq (utf8 \"é\")) is (-61 -87)."
  [s]
  (.getBytes ^String s "UTF-8"))

(defn- cat-bytes
  "The byte arrays joined end to end into a new one: a salt is a scope, a
   length and a name. (seq (cat-bytes (utf8 \"ab\") (u32 2))) is
   (97 98 0 0 0 2)."
  ^bytes [& arrs]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes a arrs] (.write out a 0 (alength a)))
    (.toByteArray out)))

(defn- ub
  "The byte at index i of the array b as a number from 0 to 255. A JVM byte
   reads as -128 to 127, and `derive` takes each byte mod the size of a
   list, which wants it unsigned. (ub (byte-array [-1]) 0) is 255."
  [b i]
  (bit-and (aget b i) 0xff))

(defn master-key
  "Derive the 64-byte master key from full name and master password.
   variant, :password unless given, is a key of `scope` and goes into the
   salt, so each variant has a master key of its own. This is the slow
   step: scrypt."
  ([full-name master-password]
   (master-key full-name master-password :password))
  ([full-name master-password variant]
   (let [name-bytes (utf8 full-name)
         salt (cat-bytes (utf8 (scope variant)) (u32 (alength name-bytes)) name-bytes)]
     ;; NOTE: Argon2 is the more modern recommended KDF and you can try that out as Spectre v4!
     (scrypt/scrypt (utf8 master-password) salt 32768 8 2 64))))

(defn- site-seed
  "The 32 bytes a site's password is read from: HMAC-SHA256, keyed with the
   master key, over the scope, the site's length, the site and the counter."
  [mkey site counter variant]
  (let [site-bytes (utf8 site)
        msg (cat-bytes (utf8 (scope variant)) (u32 (alength site-bytes)) site-bytes (u32 counter))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. mkey "HmacSHA256")))]
    (.doFinal mac msg)))

(defn derive
  "Derive a password for a site from the master key.
   The third argument is a map, every key optional:
     :counter   1 unless given. Another number, another result.
     :variant   :password unless given, or :login, or :answer. It has to be
                the variant mkey was made with, and nothing checks that.
     :template  :long unless given: a key of `templates`, the shape of the
                result. The variant does not choose it. A login that reads
                like a name asks for :name, an answer for :phrase."
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
  "Convenience: derive a site password directly from name and master password.
   opts is the map `derive` takes. Its :variant is given to `master-key` as
   well, so the two always agree."
  [full-name master-password site opts]
  (derive (master-key full-name master-password (:variant opts :password)) site opts))

(comment ;; probes

  ;; 0. simple cases
  (master-key "Alice" "a b c d" :password)
  (alength (master-key "Alice" "a b c d" :password))
  (take 8 (master-key "Alice" "a b c d" :password))
  (take 8 (master-key "Alice" "a b c d" :login))
  (derive (master-key "Alice" "a b c d" :password) "a.example.com" {:variant :password :template :long})

  ;; 1. different site
  (derive (master-key "Alice" "a b") "a.example.com")
  (derive (master-key "Alice" "a b") "A.example.com")

  ;; 2. different variant
  (password "Alice" "a b c" "v.example.com" {:variant :password})
  (password "Alice" "a b c" "v.example.com" {:variant :login})
  (password "Alice" "a b c" "v.example.com" {:variant :answer})

  ;; 3. different template
  (password "Alice" "a b c d" "t.example.com" {:variant :password})
  (password "Alice" "a b c d" "t.example.com" {:variant :password :template :long})
  (password "Alice" "a b c d" "t.example.com" {:variant :password :template :maximum})
  (password "Alice" "a b c d" "t.example.com" {:variant :password :template :pin})

  ;; 4. counter bump
  (password "Bob" "b c d e" "b.example.com" {:variant :password :template :medium})
  (password "Bob" "b c d e" "b.example.com" {:variant :password :template :medium})
  (password "Charlie" "c d e" "c.example.com" {:variant :password :template :short})

  ;; real-world scenarios: clojars, github, hackernews
  ;; for Aidan Pace of Cambridge, MA (aygp-dr on github, apace on hackernews,
  ;; apace@defrecord.com): his master password is xkcd's four words in French,
  ;; so it is no secret, and a real one is typed at a prompt and is in no file
  (def full-name "Aidan Pace")
  (def master-password "correct cheval batterie agrafe")

  ;; existing accounts keep their names (aygp-dr, apace): only the password
  ;; is derived, and set once on the site's own change-password page
  (password full-name master-password "github.com" {:variant :password})
  (password full-name master-password "news.ycombinator.com" {:variant :password})

  ;; leaked, or time for another: bump the counter, nothing else moves
  (password full-name master-password "github.com" {:variant :password :counter 2})

  ;; a new account: the name can be derived too, and :name is the template
  ;; that reads like one (without it a login is fourteen characters of :long)
  (password full-name master-password "clojars.org" {:variant :login :template :name})
  (password full-name master-password "clojars.org" {:variant :login})
  (password full-name master-password "clojars.org" {:variant :password})

  ;; name taken: bump the login's counter, the password stays where it was
  (password full-name master-password "clojars.org" {:variant :login :template :name :counter 2})

  ;; derive takes a map: a bare :login is ignored, so these two are one
  ;; password and no login (clj-kondo flags the first: expected map, received
  ;; keyword)
  (derive (master-key full-name master-password) "clojars.org" :login)
  (derive (master-key full-name master-password) "clojars.org")

  ;; the map is not enough: the master key has to be the :login one too, and
  ;; the second of these is what password gives
  (derive (master-key full-name master-password) "clojars.org" {:variant :login})
  (derive (master-key full-name master-password :login) "clojars.org" {:variant :login})

  ;; every site, before the Red Line gets from Harvard to Central: scrypt
  ;; once for each variant, and derive is instant after that
  (let [login-key (master-key full-name master-password :login)
        password-key (master-key full-name master-password :password)]
    (for [site ["clojars.org" "github.com" "news.ycombinator.com"]]
      {:site site
       :login (derive login-key site {:variant :login :template :name})
       :password (derive password-key site {:variant :password})}))

  ;; exact every time, to the letter: his name and the site
  (= (password "aidan pace" master-password "github.com" {})
     (password "Aidan Pace" master-password "github.com" {}))
  (= (password full-name master-password "www.github.com" {})
     (password full-name master-password "github.com" {}))

  ;; a bank that asks where he grew up: Cambridge is true and easy to find,
  ;; this is neither, and it is what the third variant is for
  (password full-name master-password "bank.example.com" {:variant :answer :template :phrase})

  ;; a hobby, and a new account, so the name is derived as well: the
  ;; Nuttall Ornithological Club, founded in 1873, meets at Harvard, a walk
  ;; from his door
  (password full-name master-password "nuttallclub.org" {:variant :login :template :name})
  (password full-name master-password "nuttallclub.org" {:variant :password})

  ;; and the radio: the MIT Radio Society, W1MX, America's oldest college
  ;; amateur station, two stops down the Red Line from the birds
  (password full-name master-password "w1mx.mit.edu" {:variant :login :template :name})
  (password full-name master-password "w1mx.mit.edu" {:variant :password})

  ;; original scenarios
  (def my-pass (password "John Doe"
                         "correct horse battery staple"
                         "example.com"
                         {:variant :password :template :maximum}))

  (assert (= "b0+aejObRu&7LB&Y#j%h" my-pass)))
