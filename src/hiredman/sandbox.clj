(ns hiredman.sandbox
  (:use [clojure.tools.logging :only [info]])
  (:require [hiredman.clojurebot.core :as core])
  (:import (java.util.concurrent FutureTask TimeUnit TimeoutException)
           (java.io File FileWriter PushbackReader StringReader)))

(def *bad-forms*
  #{'alter-var-root
    'alterRoot
    'intern
    'eval
    'def
    'catch
    'load-string
    'load-reader
    'clojure.core/addMethod
    'hiredman.clojurebot/bot})

(def *default-timeout* 10) ; in seconds

(def *secure?* true)

(defmacro defering-security-manager [sm]
  (let [sm-name (gensym 'sm)
        methods (for [[method-name methods]
                      (group-by #(.getName %)
                                (.getDeclaredMethods SecurityManager))
                      :when (.startsWith method-name "check")
                      :let [methods (filter
                                     #(java.lang.reflect.Modifier/isPublic
                                       (.getModifiers %))
                                     methods)]]
                  `(~(symbol method-name)
                    ~@(for [[argc [method]] (group-by
                                             #(count (.getParameterTypes %))
                                             methods)
                            :let [args (vec (map-indexed
                                             (comp symbol str)
                                             (take argc (repeat "arg"))))]]
                        `(~args
                          (when *secure?*
                            (. ~sm-name ~(symbol method-name) ~@args))))))]
    `(let [~sm-name ~sm]
       (proxy [SecurityManager] []
         ~@methods))))

(defn enable-security-manager []
  (info "enable-security-manager")
  (System/setSecurityManager
   (let [sm (SecurityManager.)]
     (defering-security-manager sm))))

;;;;;;;; Chousuke
(defn thunk-timeout [thunk seconds]
  (let [task (FutureTask.
              #(binding [*secure?* true]
                 (thunk)))
        thr (Thread. task)]
    (try
      (.start thr)
      (.get task seconds TimeUnit/SECONDS)
      (catch TimeoutException e
        (.cancel task true)
        (.stop thr (Exception. "Thread stopped!"))
        (pr-str "Execution Timed Out")))))

(defn wrap-exceptions
  ([f]
     (wrap-exceptions f #(do
                           (.printStackTrace %)
                           (.getMessage %))))
  ([f exception-handler]
     (try (f) (catch Exception e (exception-handler e)))))
;;;;;;;;;;;

(defn empty-perms-list []
  (doto (java.security.Permissions.)
    (.add (RuntimePermission. "accessDeclaredMembers"))))

(defn domain [perms]
  (java.security.ProtectionDomain.
   (java.security.CodeSource.
    nil
    (cast java.security.cert.Certificate nil))
   perms))

(defn context [dom]
  (java.security.AccessControlContext.
   (into-array [dom])))

(defn priv-action [thunk]
  (reify java.security.PrivilegedAction
    (run [_] (thunk))))

(defn sandbox [thunk context]
  (java.security.AccessController/doPrivileged
   (priv-action thunk)
   context))

(defn write-test []
  (doto (-> "foo.txt" File. FileWriter.) (.write "foo") .close))

(defn de-fang
  "looks through the macroexpand of a form for things I don't allow"
  [form notallowed]
  (if (coll? form)
    (when (not
           (some notallowed
                 (tree-seq coll?
                           #(let [a (macroexpand %)]
                              (if (coll? a)
                                (seq a)
                                (list a)))
                           form)))
      form)
    form))

(defn cond-eval [pred form]
  (if (pred form)
    (eval form)
    (throw (java.lang.Exception. "DENIED"))))

(defn killall-thrdgrp [thg]
  (let [a (make-array Thread (.activeCount thg))
        _ (.enumerate thg a true)]
    (map #(.stop % (Exception. "KILLED")))
    (.destroy thg)))

(defn save-to-gensym [x]
  (let [a (gensym)
        b (list 'def a x)]
    (eval b)
    (str a)))

(defn my-doc []
  (let [arg-name (gensym)]
    (list 'defmacro 'my-doc [arg-name]
          `(let [m# (meta (resolve ~arg-name))
                 al# (:arglists m#)
                 docstring# (:doc m#)]
             (if m#
               (.replaceAll (str al# "; " docstring# ) "\\s+" " ")
               '(symbol ~(hiredman.clojurebot.core/befuddled)))))))

(defn force-lazy-seq
  "if passed a lazy seq, forces seq with doall, if not return what is passed"
  [s]
  (or (and (instance? clojure.lang.LazySeq s) (doall s)) s))

(defn eval-in-box-helper [form {:keys [print-length print-level]}]
  (let [result (cond-eval #(de-fang % *bad-forms*) form)]
    (.close *out*)
    (.close *err*)
    (binding [*print-length* print-length
              *print-level* print-level]
      (let [r (prn-str (force-lazy-seq result))
            o (print-str (.toString *out*))
            e (print-str (.toString *err*))]
        [o e (when (or result (.equals o "")) r)]))))

(defn call-method
  "Calls a private or protected method.

   params is a vector of classes which correspond to the arguments to
   the method e

   obj is nil for static methods, the instance object otherwise.

   The method-name is given a symbol or a keyword (something Named)."
  [klass method-name params obj & args]
  (-> klass (.getDeclaredMethod (name method-name)
                                (into-array Class params))
      (.invoke obj (into-array Object args))))


(defprotocol Evaluator
  (evil [evaluator form]))

(extend-type ClassLoader
  Evaluator
  (evil [cl form-str]
    (read-string
     (let [old-cl (.getContextClassLoader (Thread/currentThread))]
       (try
         (.setContextClassLoader (Thread/currentThread) cl)
         (let [secure *secure?*
               rt (.loadClass cl "clojure.lang.RT")
               compiler (.loadClass cl "clojure.lang.Compiler")
               var- (fn [s]
                      (call-method
                       rt :var [String String] nil (namespace s) (name s)))
               class (fn [x] (.loadClass cl (name x)))
               deref (fn [x] (call-method (.getClass x) :deref [] x))
               invoke (fn [x &  args] (call-method (.getClass x) :invoke []))
               read-string (fn [s]
                             (binding [*secure?* secure]
                               (call-method rt :readString [String] nil s)))
               eval (fn [f]
                      (binding [*secure?* secure]
                        (call-method compiler :eval [Object] nil f)))]
           (thunk-timeout
            (fn []
              (sandbox #(eval (read-string (format "(pr-str %s)" form-str)))
                       (context (domain (empty-perms-list)))))
            *default-timeout*))
         (finally
          (.setContextClassLoader (Thread/currentThread) old-cl)))))))

(defn eval-in-box [_string sb-ns class-loader]
  (enable-security-manager)
  (let [f `(do
             (with-open [o# (java.io.StringWriter.)
                         e# (java.io.StringWriter.)]
               (binding [*out* o#
                         *err* e#
                         *read-eval* false
                         *print-level* 10
                         *print-length* 5
                         *ns* (find-ns 'clojure.core)]
                 ~(my-doc)
                 (ns ~sb-ns
                   (:use [clojure.repl]))
                 (alter-var-root (resolve '~'doc)
                                 (constantly (resolve '~'my-doc)))
                 (let [f# (read-string ~_string)
                       good?# (if (and (coll? f#)
                                       (not (empty? f#)))
                                (when (not
                                       (some '~*bad-forms*
                                             (tree-seq coll?
                                                       (fn [i#]
                                                         (let [a# (macroexpand
                                                                   i#)]
                                                           (if (coll? a#)
                                                             (seq a#)
                                                             (list a#))))
                                                       f#)))
                                  f#)
                                true)
                       r# (pr-str (try
                                    (when-not good?#
                                      (throw (Exception. "SANBOX DENIED")))
                                    (eval f#)
                                    (catch Throwable t#
                                      t#)))]
                   [(.toString (doto o# .close))
                    (.toString (doto e# .close))
                    r#]))))
        thunk (fn []
                (binding [*secure?* true]
                  (evil class-loader f)))]
    (thunk)))

(->> SecurityManager .getDeclaredMethods
     (map bean)
     (filter
      #(java.lang.reflect.Modifier/isPublic (:modifiers %)))
     (filter #(.startsWith (:name %) "check")))


