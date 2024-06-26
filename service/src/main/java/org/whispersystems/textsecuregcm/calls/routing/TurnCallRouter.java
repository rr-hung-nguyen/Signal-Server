/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.calls.routing;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.Util;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Returns routes based on performance tables, manually routing tables, and target routing. Falls back to a random Turn
 * instance that the server knows about.
 */
public class TurnCallRouter {

  private final Logger logger = LoggerFactory.getLogger(TurnCallRouter.class);

  private final Supplier<CallDnsRecords> callDnsRecords;
  private final Supplier<CallRoutingTable> performanceRouting;
  private final Supplier<CallRoutingTable> manualRouting;
  private final DynamicConfigTurnRouter configTurnRouter;
  private final Supplier<DatabaseReader> geoIp;
  // controls whether instance IPs are shuffled. using if & boolean is ~5x faster than a function pointer
  private final boolean stableSelect;

  public TurnCallRouter(
      @Nonnull Supplier<CallDnsRecords> callDnsRecords,
      @Nonnull Supplier<CallRoutingTable> performanceRouting,
      @Nonnull Supplier<CallRoutingTable> manualRouting,
      @Nonnull DynamicConfigTurnRouter configTurnRouter,
      @Nonnull Supplier<DatabaseReader> geoIp,
      boolean stableSelect
  ) {
    this.performanceRouting = performanceRouting;
    this.callDnsRecords = callDnsRecords;
    this.manualRouting = manualRouting;
    this.configTurnRouter = configTurnRouter;
    this.geoIp = geoIp;
    this.stableSelect = stableSelect;
  }

  /**
   * Gets Turn Instance addresses. Returns both the IPv4 and IPv6 addresses. Prefers to match the IP protocol of the
   * client address in datacenter selection. Returns 2 instance options of the preferred protocol for every one instance
   * of the other.
   * @param aci aci of client
   * @param clientAddress IP address to base routing on
   * @param instanceLimit max instances to return options for
   */
  public TurnServerOptions getRoutingFor(
      @Nonnull final UUID aci,
      @Nonnull final Optional<InetAddress> clientAddress,
      final int instanceLimit
  ) {
    try {
      return getRoutingForInner(aci, clientAddress, instanceLimit);
    } catch(Exception e) {
      logger.error("Failed to perform routing", e);
      return new TurnServerOptions(this.configTurnRouter.getHostname(), null, this.configTurnRouter.randomUrls());
    }
  }

  TurnServerOptions getRoutingForInner(
      @Nonnull final UUID aci,
      @Nonnull final Optional<InetAddress> clientAddress,
      final int instanceLimit
  ) {
    if (instanceLimit < 1) {
      throw new IllegalArgumentException("Limit cannot be less than one");
    }

    String hostname = this.configTurnRouter.getHostname();

    List<String> targetedUrls = this.configTurnRouter.targetedUrls(aci);
    if(!targetedUrls.isEmpty()) {
      return new TurnServerOptions(hostname, null, targetedUrls);
    }

    if(clientAddress.isEmpty() || this.configTurnRouter.shouldRandomize()) {
      return new TurnServerOptions(hostname, null, this.configTurnRouter.randomUrls());
    }

    CityResponse geoInfo;
    try {
      geoInfo = geoIp.get().city(clientAddress.get());
    } catch (IOException | GeoIp2Exception e) {
      throw new RuntimeException(e);
    }
    Optional<String> subdivision = !geoInfo.getSubdivisions().isEmpty()
        ? Optional.of(geoInfo.getSubdivisions().getFirst().getIsoCode())
        : Optional.empty();

    List<String> datacenters = this.manualRouting.get().getDatacentersFor(
        clientAddress.get(),
        geoInfo.getContinent().getCode(),
        geoInfo.getCountry().getIsoCode(),
        subdivision
    );

    if (datacenters.isEmpty()){
      datacenters = this.performanceRouting.get().getDatacentersFor(
          clientAddress.get(),
          geoInfo.getContinent().getCode(),
          geoInfo.getCountry().getIsoCode(),
          subdivision
      );
    }

    List<String> urlsWithIps = getUrlsForInstances(
        selectInstances(
            datacenters,
            instanceLimit,
            (clientAddress.get() instanceof Inet6Address)
        ));
    return new TurnServerOptions(hostname, urlsWithIps, minimalRandomUrls());
  }

  // Includes only the udp options in the randomUrls
  private List<String> minimalRandomUrls(){
    return this.configTurnRouter.randomUrls().stream()
        .filter(s -> s.startsWith("turn:") && !s.endsWith("transport=tcp"))
        .toList();
  }

  private List<String> selectInstances(List<String> datacenters, int limit, boolean preferV6) {
    if(datacenters.isEmpty() || limit == 0) {
      return Collections.emptyList();
    }
    int numV6 = preferV6 ? (limit - limit / 3) : limit / 3;
    int numV4  = limit - numV6;

    CallDnsRecords dnsRecords = this.callDnsRecords.get();
    List<InetAddress> ipv4Selection = datacenters.stream()
        .flatMap(dc -> randomNOf(dnsRecords.aByRegion().get(dc), limit, stableSelect).stream())
        .toList();
    List<InetAddress> ipv6Selection = datacenters.stream()
        .flatMap(dc -> randomNOf(dnsRecords.aaaaByRegion().get(dc), limit, stableSelect).stream())
        .toList();

    // increase numV4 if not enough v6 options. vice-versa is also true
    numV4 = Math.max(numV4, limit - ipv6Selection.size());
    ipv4Selection = ipv4Selection.stream().limit(numV4).toList();
    ipv6Selection = ipv6Selection.stream().limit(limit - ipv4Selection.size()).toList();

    return Stream.concat(
        ipv4Selection.stream().map(InetAddress::getHostAddress),
        // map ipv6 to RFC3986 format i.e. surrounded by brackets
        ipv6Selection.stream().map(i -> String.format("[%s]", i.getHostAddress()))
    ).toList();
  }

  private static <E> List<E> randomNOf(List<E> values, int n, boolean stableSelect) {
    return stableSelect ? Util.randomNOfStable(values, n) : Util.randomNOfShuffled(values, n);
  }

  private static List<String> getUrlsForInstances(List<String> instanceIps) {
    return instanceIps.stream().flatMap(ip -> Stream.of(
            String.format("turn:%s", ip),
            String.format("turn:%s:80?transport=tcp", ip),
            String.format("turns:%s:443?transport=tcp", ip)
        )
    ).toList();
  }
}
