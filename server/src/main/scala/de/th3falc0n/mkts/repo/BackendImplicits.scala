package de.th3falc0n.mkts.repo

import cats.effect.{ ExitCode, IO }
import de.th3falc0n.mkts.Models.{ AddressList, AddressSource }
import de.th3falc0n.mkts.backend.MikrotikConnection
import de.th3falc0n.mkts.ip.{ IP, IPMerger }
import io.circe.{ Codec, Decoder, Encoder }
import org.slf4j.LoggerFactory
import sttp.client3.{ HttpURLConnectionBackend, basicRequest }
import sttp.model.Uri

import java.net.URI

object BackendImplicits  {
  implicit class BackendAddressSource(addressSource: AddressSource) {
    private val uri = Uri(URI.create(addressSource.name.string))
    private val logger = LoggerFactory.getLogger(s"iplist-${addressSource.name.string}")

    def fetch: Seq[IP] = {
      logger.info("Fetching")
      val request = basicRequest.get(uri)

      val backend = HttpURLConnectionBackend()
      val response = request.send(backend)

      val ips = response
        .body.getOrElse("")
        .split("\n")
        .filter(_.nonEmpty)
        .map(_.split(' ').head)
        .filter(ip => "^[0-9][0-9./]*$".r.matches(ip))
        .map(IP.fromString)
        .filter {
          case ip if IP.fromString("10.0.0.0/8").contains(ip) => false
          case ip if IP.fromString("172.16.0.0/12").contains(ip) => false
          case ip if IP.fromString("192.168.0.0/16").contains(ip) => false
          case _ => true
        }

      logger.info("Got {} IPs", ips.length, addressSource.name.string)
      ips
    }
  }

  implicit class BackendAddressList(addressList: AddressList) {
    private val logger = LoggerFactory.getLogger("AddressList-" + addressList.name.string)

    def update: IO[ExitCode] = IO {
      try {
        logger.info("Updating list {} with {} sources", addressList.name.string, addressList.sources.length)
        val ips = addressList.sources.flatMap(_.fetch)

        logger.info("Got {} unique IPs", ips.length)
        val merged = IPMerger.mergeIPs(ips)

        logger.info("Reduced to {} unique list entries", merged.length)
        MikrotikConnection.updateList(addressList.name.string, merged)

        logger.info("Updated list {} with {} unique IPs", addressList.name.string, merged.length)
        ExitCode.Success
      }
      catch {
        case e: Exception => {
          logger.error("Error updating list {}", addressList.name.string, e)
          ExitCode.Error
        }
      }
    }
  }
}