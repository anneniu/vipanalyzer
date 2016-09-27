package com.kunyan.vipanalyzer.parser.streaming

import java.util.Date
import com.kunyan.vipanalyzer.util.{StringUtil, RedisUtil, DBUtil}
import com.kunyan.vipanalyzer.config.Platform
import com.kunyan.vipanalyzer.db.LazyConnections
import com.kunyan.vipanalyzer.logger.VALogger
import org.jsoup.Jsoup
import scala.util.control.Breaks._

/**
  * Created by niujiaojiao on 2016/6/7.
  * 摩尔金融
  */
object MoerStreamingParser {
  /**
    * 解析获取信息
    *
    * @param html 将要解析的字符串
    */
  def parse(pageUrl: String, html: String, lazyConn: LazyConnections, topic: String) = {

    val doc = Jsoup.parse(html, "UTF-8")
    val list = doc.getElementsByAttributeValue("class", "blu authortab-list").select("tr")

    val cstmt = lazyConn.mysqlConn.prepareCall("{call proc_InsertMoerNewArticle(?,?,?,?,?,?,?,?)}")

    val urlSql = lazyConn.mysqlConn.prepareStatement("select * from article_info where url = ?")

    val lastTitle = lazyConn.jedisHget(RedisUtil.REDIS_HASH_NAME, pageUrl)
    val timeStamp = new Date().getTime

    breakable {

      for (i <- 0 until list.size) {

        val child = list.get(i)
        val title = child.select("a").get(0).text()

        if (i == 0) {

          if (title != lastTitle) {
            lazyConn.jedisHset(RedisUtil.REDIS_HASH_NAME, pageUrl, title)
          } else {
            break()
          }

        }

        if (title == lastTitle) {
          break()
        }


        try {

          val userId = pageUrl.split("theId=")(1)
          val title = list.get(i).select("a").get(0).text()
          val read = 0
          val buy = 0
          val price = 0.0
          val url = "http://moer.jiemian.com/" + list.get(i).select("a").get(0).attr("href")
          val stock = ""

          //查询url是否重复
          urlSql.setString(1,url)
          val blFlag = urlSql.executeQuery().next()

          if(blFlag){
            break()
          }

          val sqlFlag = DBUtil.insertCall(cstmt, userId, title, read, buy, price, url, timeStamp, stock)

          if (sqlFlag) {
            lazyConn.sendTask(topic, StringUtil.toJson(Platform.MOER.id.toString, 0, url))
          } else {
            VALogger.warn("MYSQL data has exception, stop topic for :  " + url)
          }

        } catch {
          case e: Exception =>
            VALogger.exception(e)
        }

      }

    }

    cstmt.close()
  }

}
