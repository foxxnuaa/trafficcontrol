/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.cdn.traffic_control.traffic_router.core.edge;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.comcast.cdn.traffic_control.traffic_router.core.hash.DefaultHashable;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.log4j.Logger;

public class Node extends DefaultHashable {
	private static final Logger LOGGER = Logger.getLogger(Node.class);
	private static final int REPLICAS = 1000;

	public enum IpVersions {
		IPV4ONLY, IPV6ONLY, BOTH
	}
	private IpVersions ipAvailableVersions;
	protected final String id;
	private String fqdn;
	private List<InetRecord> ipAddresses;
	private List<InetRecord> unavailableIpAddresses;
	private int port;
	private final Map<String, Cache.DeliveryServiceReference> deliveryServices = new HashMap<>();
	private final Set<String> capabilities = new HashSet<>();
	private int httpsPort = 443;

	public Node(final String id) {
		this.id = id;
		generateHashes(id, REPLICAS);
	}

	// alternate constructor
	public Node(final String id, final String hashId, final int hashCount) {
		this.id = id;
		generateHashes(hashId, hashCount > 0 ? hashCount : REPLICAS);
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		} else if (obj instanceof Node) {
			final Node rhs = (Node) obj;
			return new EqualsBuilder()
			.append(getId(), rhs.getId())
			.isEquals();
		} else {
			return false;
		}
	}

	public String getFqdn() {
		return fqdn;
	}

	public String getId() {
		return id;
	}

	public List<InetRecord> getIpAddresses(final JsonNode ttls, final Resolver resolver) {
		return getIpAddresses(ttls, resolver, true);
	}

	@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
	public List<InetRecord> getIpAddresses(final JsonNode ttls, final Resolver resolver, final boolean ip6RoutingEnabled) {
		if(ipAddresses == null || ipAddresses.isEmpty()) {
			ipAddresses = resolver.resolve(this.getFqdn()+".");
		}
		if(ipAddresses == null) { return null; }
		final List<InetRecord> ret = new ArrayList<InetRecord>();
		for (final InetRecord ir : ipAddresses) {
			if (ir.isInet6() && !ip6RoutingEnabled) {
				continue;
			}

			long ttl = 0;

			if(ttls == null) {
				ttl = -1;
			} else if(ir.isInet6()) {
				ttl = JsonUtils.optLong(ttls, "AAAA");
			} else {
				ttl = JsonUtils.optLong(ttls, "A");

			}

			ret.add(new InetRecord(ir.getAddress(), ttl));
		}
		return ret;
	}

	public int getPort() {
		return port;
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder(1, 31)
		.append(getId())
		.toHashCode();
	}

	public void addCapabilities(final Set<String> capabilities) {
		this.capabilities.addAll(capabilities);
	}

	public Set<String> getCapabilities() {
		return this.capabilities;
	}

	public void setDeliveryServices(final Collection<Cache.DeliveryServiceReference> deliveryServices) {
		for (final Cache.DeliveryServiceReference deliveryServiceReference : deliveryServices) {
			this.deliveryServices.put(deliveryServiceReference.getDeliveryServiceId(), deliveryServiceReference);
		}
	}

	public boolean hasDeliveryService(final String deliveryServiceId) {
		return deliveryServices.containsKey(deliveryServiceId);
	}

	public void setFqdn(final String fqdn) {
		this.fqdn = fqdn;
	}

	public void setIpAddresses(final List<InetRecord> ipAddresses) {
		this.ipAddresses = ipAddresses;
	}

	public void setPort(final int port) {
		this.port = port;
	}

	@Override
	public String toString() {
		return "Node [id=" + id + "] ";
	}

	boolean isAvailable = false;
	boolean hasAuthority = false;
	public void setIsAvailable(final boolean isAvailable) {
		this.hasAuthority = true;
		this.isAvailable = isAvailable;
	}
	public boolean hasAuthority() {
		return hasAuthority;
	}
	public boolean isAvailable() {
		return isAvailable;
	}
	InetAddress ip4;
	InetAddress ip6;
	public void setIpAddress(final String ip, final String ip6, final long ttl) throws UnknownHostException {
		this.ipAddresses = new ArrayList<InetRecord>();
		this.unavailableIpAddresses = new ArrayList<InetRecord>();

		if (ip != null && !ip.isEmpty()) {
			this.ip4 = InetAddress.getByName(ip);
			ipAddresses.add(new InetRecord(ip4, ttl));
		} else {
			LOGGER.error(getFqdn() + " - no IPv4 address configured!");
		}

		if (ip6 != null && !ip6.isEmpty()) {
			final String ip6addr = ip6.replaceAll("/.*", "");
			this.ip6 = Inet6Address.getByName(ip6addr);
			ipAddresses.add(new InetRecord(this.ip6, ttl));
		} else {
			LOGGER.error(getFqdn() + " - no IPv6 address configured!");
		}
	}
	public InetAddress getIp4() {
		return ip4;
	}
	public InetAddress getIp6() {
		return ip6;
	}

	@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
	public void setState(final JsonNode state) {
		final boolean isAvailable = JsonUtils.optBoolean(state, "isAvailable", true);
		final boolean ipv4Available = JsonUtils.optBoolean(state, "ipv4Available", true);
		final boolean ipv6Available = JsonUtils.optBoolean(state, "ipv6Available", true);

		final List<InetRecord> newlyAvailable = new ArrayList<>();
		final List<InetRecord> newlyUnavailable = new ArrayList<>();

		if (ipv4Available && !ipv6Available) {
			this.ipAvailableVersions = IpVersions.IPV4ONLY;
		} else if (ipv6Available && !ipv4Available) {
			this.ipAvailableVersions = IpVersions.IPV6ONLY;
		} else {
			this.ipAvailableVersions = IpVersions.BOTH;
		}

		for (final InetRecord record : ipAddresses) {
			if (record.getAddress().equals(ip4) && !ipv4Available) {
				newlyUnavailable.add(record);
			}
			if (record.getAddress().equals(ip6) && !ipv6Available) {
				newlyUnavailable.add(record);
			}
		}

		for (final InetRecord record : unavailableIpAddresses) {
			if (record.getAddress().equals(ip4) && ipv4Available) {
				newlyAvailable.add(record);
			}
			if (record.getAddress().equals(ip6) && ipv6Available) {
				newlyAvailable.add(record);
			}
		}

		ipAddresses.addAll(newlyAvailable);
		ipAddresses.removeAll(newlyUnavailable);
		unavailableIpAddresses.addAll(newlyUnavailable);
		unavailableIpAddresses.removeAll(newlyAvailable);

		this.setIsAvailable(isAvailable);


	}

	public int getHttpsPort() {
		return httpsPort;
	}

	public void setHttpsPort(final int httpsPort) {
		this.httpsPort = httpsPort;
	}

	public IpVersions getIpAvailableVersions() {
		return ipAvailableVersions;
	}

	public void setIpAvailableVersions(final IpVersions ipAvailableVersions) {
		this.ipAvailableVersions = ipAvailableVersions;
	}
}
