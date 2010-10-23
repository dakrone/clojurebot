;; posts lines containing urls to a delicious account
(ns clojurebot.delicious
  (:require [clj-http.client :as http]))

(def url-reg #"[A-Za-z]+://[^  ^/]+\.[^  ^/]+[^ ]+")

(def pastebins
  #"(fpaste\.org|dpaste\.*|gist\.github\.com|paste.lisp.org/display|.*pastebin\.com|p\.hagelb\.org|pastebin\.org|paste\.pocoo\.org/show|pastie\.org|sprunge\.us)")

;;#"(\w+://.*?)[.>]*(?: |$)"

#_(defn post
    "posts a url to the delicious account of [user pass]"
    [[user pass] url descr tag]
    (util/shell (str "fetch -o /dev/null https://" user ":" pass "@api.del.icio.us/v1/posts/add?url=" (URLEncoder/encode url) "&description=" (URLEncoder/encode descr) "&tags=" (URLEncoder/encode tag))))

(defn post
  "posts a url to the delicious account of [user pass]"
  [[user pass] url descr tag]
  (http/get "https://api.del.icio.us/v1/posts/add"
            {:query-params
             {"url" url
              "description" descr
              "tags" tag}
             :basic-auth [user pass]}))

(defn contains-url? [{:keys [message channel config]}]
  (and message
       (re-find url-reg message)
       channel
       (:delicious config)))

(defn delicious [{:keys [message channel sender config]}]
  (let [url (re-find url-reg message)
        desc channel
        tag (str sender " " channel
                 (when (re-find #"lisppaste" sender)
                   (str " " (first (.split desc " ")))))
        tag (if (re-find pastebins url)
              (str tag " pastbin")
              tag)]
    (post (:delicious config) url desc tag)))
