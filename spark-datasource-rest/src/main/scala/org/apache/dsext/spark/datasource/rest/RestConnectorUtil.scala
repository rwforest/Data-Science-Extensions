/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dsext.spark.datasource.rest

import java.io._
import java.net.{HttpURLConnection, URL, URLEncoder, HttpCookie}

import scala.annotation.switch
import scala.collection.mutable.ArrayBuffer

import scalaj.http.{Http, HttpOptions, Token}

/**
 * This object contains all utility functions for reading/writing data from/to remote rest service
 */

object RestConnectorUtil {

  def callRestAPI(uri: String,
                     data: String,
                     dataSeq: Seq[(String, String)],
                     method: String,
                     oauthCredStr: String,
                     userCredStr: String,
                     connStr: String,
                     contentType: String,
                     respType: String,
                     restHeader: String,
                     authToken: String,
                     authType: String,
                     queryType: String,
                     cookie: String): Any = {


    var httpc = (method: @switch) match {
      case "GET" =>
        {
          if (authToken.isEmpty && cookie.isEmpty)
            Http(addQryParmToUri(queryType, uri, data))
          else
          {
            // Either cookie or auth token is needed, not both
            if (cookie.nonEmpty)
            {
              val co = cookie.split("=")

              if (co.length > 0)
                if (authToken.nonEmpty)
                  Http(addQryParmToUri(queryType, uri, data)).header(restHeader, authToken).cookie(co(0), co(1))
                else
                  Http(addQryParmToUri(queryType, uri, data)).cookie(co(0), co(1))
              else
                throw new Exception("Cookie must be in the format of Name=Value")
            }
            else
              Http(addQryParmToUri(queryType, uri, data)).header(restHeader, authToken)
          }
        }
      case "PUT" => Http(uri).put(data).header("content-type", contentType)
      case "DELETE" => Http(uri).method("DELETE")
      case "POST" => if (contentType == "application/json") Http(uri).postData(data).header("content-type", contentType) else Http(uri).postForm(dataSeq)
    }

    val conns = connStr.split(":")
    val connProp = Array(conns(0).toInt, conns(1).toInt)

    httpc = httpc.timeout(connTimeoutMs = connProp(0),
      readTimeoutMs = connProp(1))

    httpc = httpc.option(HttpOptions.allowUnsafeSSL)

    if (oauthCredStr == "") {
      httpc = if (userCredStr == "") httpc else {
        val usrCred = userCredStr.split(":")
        httpc.auth(usrCred(0), usrCred(1))
      }
    }
    else {
      val oauthd = oauthCredStr.split(":")
      val consumer = Token(oauthd(0), oauthd(1))
      val accessToken = Token(oauthd(2), oauthd(3))
      httpc.oauth(consumer, accessToken)
    }

    // print("in callRestAPI final http : " + httpc + "\n")

    val resp = (respType : @switch) match {
      case "BODY" => httpc.asString.body
      case "BODY-BYTES" => httpc.asBytes.body
      case "BODY-STREAM" => getBodyStream(httpc)
      case "CODE" => httpc.asString.code
      case "HEADERS" => httpc.asString.headers
      case "LOCATION" => httpc.asString.location.mkString(" ")
    }

    val outCookie : String = (respType : @switch) match {
      case "BODY" => if (httpc.asString.cookies.nonEmpty) httpc.asString.cookies(0).toString else ""
    }

    (resp, outCookie)
  }

  private def addQryParmToUri(queryType: String, uri: String, data: String) : String = {
    if (queryType.toLowerCase == "querystring")
    {
      if (uri contains "?") uri + "&" + data else uri + "?" + data
    }
    else if (queryType.toLowerCase == "inline")
    {
      if (data contains "&")
        throw new Exception("Please limit to 1 row for inline")

      val key = URLEncoder.encode(data.split("=")(0))
      val value = URLEncoder.encode(data.split("=")(1))
      uri.replace("{key}", key).replace("{value}", value)
    }
    else
      throw new Exception("Only querystring and inline are supported")
  }

  private def convertToQryParm(data: String) : List[(String, String)] = {
      data.substring(1, data.length - 1).split(",").map(_.split(":"))
        .map{ case Array(k, v) => (k.substring(1, k.length-1), v.substring(1, v.length-1))}
                   .toList
  }

  private def getBodyStream(httpReq: scalaj.http.HttpRequest) : InputStream = {

    val conn = (new URL(httpReq.urlBuilder(httpReq))).openConnection.asInstanceOf[HttpURLConnection]

    HttpOptions.method(httpReq.method)(conn)

    httpReq.headers.reverse.foreach{ case (name, value) =>
          conn.setRequestProperty(name, value)
    }

    httpReq.options.reverse.foreach(_(conn))

    httpReq.connectFunc(httpReq, conn)

    conn.getInputStream

  }

  def prepareSeqInput(keys: Array[String], values: Array[String]) : Seq[(String, String)] = {

    (keys zip values).toSeq

  }

  def prepareJsonInput(keys: Array[String], values: Array[String]) : String = {

    val keysLength = keys.length
    var cnt = 0
    val outArrB : ArrayBuffer[String] = new ArrayBuffer[String](keysLength)

    while (cnt < keysLength) {
        outArrB += "\"" + keys(cnt) + "\":\"" + values(cnt) + "\""
        cnt += 1
    }

    "{" + outArrB.mkString(",") + "}"

  }

  def prepareTextInput(keys: Array[String], values: Array[String]) : String = {

    val keysLength = keys.length
    var cnt = 0
    val outArrB : ArrayBuffer[String] = new ArrayBuffer[String](keysLength)

    while (cnt < keysLength) {
        outArrB += URLEncoder.encode(keys(cnt)) + "=" + URLEncoder.encode(values(cnt))
        cnt += 1
    }

    outArrB.mkString("&")

  }

  def prepareJsonOutput(keys: Array[String], values: Array[String], resp: String, cookie: String) : String = {

    val keysLength = keys.length
    var cnt = 0
    val outArrB : ArrayBuffer[String] = new ArrayBuffer[String](keysLength)

    while (cnt < keysLength) {
        outArrB += "\"" + keys(cnt) + "\":\"" + values(cnt) + "\""
        cnt += 1
    }

    if (cookie.nonEmpty)
      outArrB += "\"cookie\":\"" + cookie + "\""

    "{" + outArrB.mkString(",") +  ",\"output\":" + resp + "}"

  }

}
