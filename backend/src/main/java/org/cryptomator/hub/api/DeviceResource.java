package org.cryptomator.hub.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.cryptomator.hub.entities.AuditEventDeviceRegister;
import org.cryptomator.hub.entities.AuditEventDeviceRemove;
import org.cryptomator.hub.entities.AuditEventVaultAccessGrant;
import org.cryptomator.hub.entities.Device;
import org.cryptomator.hub.entities.LegacyDevice;
import org.cryptomator.hub.entities.User;
import org.cryptomator.hub.validation.NoHtmlOrScriptChars;
import org.cryptomator.hub.validation.OnlyBase64Chars;
import org.cryptomator.hub.validation.ValidId;
import org.cryptomator.hub.validation.ValidJWE;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.NoCache;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Path("/devices")
public class DeviceResource {

	private static final Logger LOG = Logger.getLogger(DeviceResource.class);

	@Inject
	JsonWebToken jwt;

	@GET
	@Path("/")
	@RolesAllowed("admin")
	@Produces(MediaType.APPLICATION_JSON)
	@Transactional
	@Operation(summary = "lists all devices matching the given ids", description = "lists for each id in the list its corresponding device. Ignores all id's where a device cannot be found")
	@APIResponse(responseCode = "200")
	public List<DeviceDto> getSome(@QueryParam("ids") List<String> deviceIds) {
		return Device.findAllInList(deviceIds).map(DeviceDto::fromEntity).toList();
	}

	@PUT
	@Path("/{deviceId}")
	@RolesAllowed("user")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transactional
	@Operation(summary = "creates or updates a device", description = "the device will be owned by the currently logged-in user")
	@APIResponse(responseCode = "201", description = "Device created or updated")
	@APIResponse(responseCode = "409", description = "Device with this key already exists")
	public Response createOrUpdate(@Valid @NotNull DeviceDto dto, @PathParam("deviceId") @ValidId String deviceId) {
		Device device;
		try {
			device = Device.findByIdAndUser(deviceId, jwt.getSubject());
		} catch (NoResultException e) {
			device = new Device();
			device.id = deviceId;
			device.owner = User.findById(jwt.getSubject());
			device.creationTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
			device.type = dto.type != null ? dto.type : Device.Type.DESKTOP; // default to desktop for backwards compatibility

			if (LegacyDevice.deleteById(device.id)) {
				assert LegacyDevice.findById(device.id) == null;
				LOG.info("Deleted Legacy Device during re-registration of Device " + deviceId);
			}
		}
		device.name = dto.name;
		device.publickey = dto.publicKey;
		device.userPrivateKey = dto.userPrivateKey;

		try {
			device.persistAndFlush();
			AuditEventDeviceRegister.log(jwt.getSubject(), deviceId, device.name, device.type);
			return Response.created(URI.create(".")).build();
		} catch (ConstraintViolationException e) {
			throw new ClientErrorException(Response.Status.CONFLICT, e);
		}
	}

	@GET
	@Path("/{deviceId}")
	@RolesAllowed("user")
	@Produces(MediaType.APPLICATION_JSON)
	@NoCache
	@Transactional
	@Operation(summary = "get the device", description = "the device must be owned by the currently logged-in user")
	@APIResponse(responseCode = "200", description = "Device found")
	@APIResponse(responseCode = "404", description = "Device not found or owned by a different user")
	public DeviceDto get(@PathParam("deviceId") @ValidId String deviceId) {
		try {
			Device device = Device.findByIdAndUser(deviceId, jwt.getSubject());
			return DeviceDto.fromEntity(device);
		} catch (NoResultException e) {
			throw new NotFoundException(e);
		}
	}

	@DELETE
	@Path("/{deviceId}")
	@RolesAllowed("user")
	@Produces(MediaType.APPLICATION_JSON)
	@Transactional
	@Operation(summary = "removes a device", description = "the device will be only be removed if the current user is the owner")
	@APIResponse(responseCode = "204", description = "device removed")
	@APIResponse(responseCode = "404", description = "device not found with current user")
	public Response remove(@PathParam("deviceId") @ValidId String deviceId) {
		if (deviceId == null || deviceId.trim().length() == 0) {
			return Response.status(Response.Status.BAD_REQUEST).entity("deviceId cannot be empty").build();
		}

		User currentUser = User.findById(jwt.getSubject());
		var maybeDevice = Device.<Device>findByIdOptional(deviceId);
		if (maybeDevice.isPresent() && currentUser.equals(maybeDevice.get().owner)) {
			maybeDevice.get().delete();
			AuditEventDeviceRemove.log(jwt.getSubject(), deviceId);
			return Response.status(Response.Status.NO_CONTENT).build();
		} else {
			return Response.status(Response.Status.NOT_FOUND).build();
		}
	}

	public record DeviceDto(@JsonProperty("id") @ValidId String id,
							@JsonProperty("name") @NoHtmlOrScriptChars @NotBlank String name,
							@JsonProperty("type") Device.Type type,
							@JsonProperty("publicKey") @NotNull @OnlyBase64Chars String publicKey,
							@JsonProperty("userPrivateKey") @NotNull @ValidJWE String userPrivateKey,
							@JsonProperty("owner") @ValidId String ownerId,
							@JsonProperty("creationTime") Instant creationTime) {

		public static DeviceDto fromEntity(Device entity) {
			return new DeviceDto(entity.id, entity.name, entity.type, entity.publickey, entity.userPrivateKey, entity.owner.id, entity.creationTime.truncatedTo(ChronoUnit.MILLIS));
		}

	}
}
