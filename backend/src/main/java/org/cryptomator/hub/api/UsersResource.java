package org.cryptomator.hub.api;

import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.cryptomator.hub.entities.AccessToken;
import org.cryptomator.hub.entities.Device;
import org.cryptomator.hub.entities.User;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.NoCache;

import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Path("/users")
@Produces(MediaType.TEXT_PLAIN)
public class UsersResource {

	@Inject
	JsonWebToken jwt;

	@PUT
	@Path("/me")
	@RolesAllowed("user")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	@Operation(summary = "update the logged-in user")
	@APIResponse(responseCode = "201", description = "user created or updated")
	public Response putMe(@Nullable @Valid UserDto dto) {
		var userId = jwt.getSubject();
		User user = User.findById(userId);
		if (user == null) {
			user = new User();
			user.id = userId;
		}
		user.name = jwt.getName();
		user.pictureUrl = jwt.getClaim("picture");
		user.email = jwt.getClaim("email");
		if (dto != null) {
			user.publicKey = dto.publicKey;
			user.privateKey = dto.privateKey;
			user.setupCode = dto.setupCode;
		}
		user.persist();
		return Response.created(URI.create(".")).build();
	}

	@GET
	@Path("/me")
	@RolesAllowed("user")
	@Produces(MediaType.APPLICATION_JSON)
	@NoCache
	@Transactional
	@Operation(summary = "get the logged-in user")
	@APIResponse(responseCode = "200", description = "returns the current user")
	@APIResponse(responseCode = "404", description = "no user matching the subject of the JWT passed as Bearer Token")
	public UserDto getMe(@QueryParam("withDevices") boolean withDevices) {
		User user = User.findById(jwt.getSubject());
		Function<Device, DeviceResource.DeviceDto> mapDevices = d -> new DeviceResource.DeviceDto(d.id, d.name, d.type, d.publickey, d.userPrivateKey, d.owner.id, d.creationTime.truncatedTo(ChronoUnit.MILLIS));
		var devices = withDevices ? user.devices.stream().map(mapDevices).collect(Collectors.toSet()) : Set.<DeviceResource.DeviceDto>of();
		return new UserDto(user.id, user.name, user.pictureUrl, user.email, devices, user.publicKey, user.privateKey, user.setupCode);
	}

	@POST
	@Path("/me/reset")
	@RolesAllowed("user")
	@NoCache
	@Transactional
	@Operation(summary = "resets the user account")
	@APIResponse(responseCode = "204", description = "deleted keys, devices and access permissions")
	public Response resetMe() {
		User user = User.findById(jwt.getSubject());
		user.publicKey = null;
		user.privateKey = null;
		user.setupCode = null;
		user.persist();
		Device.deleteByOwner(user.id);
		AccessToken.deleteByUser(user.id);
		return Response.noContent().build();
	}

	@GET
	@Path("/")
	@RolesAllowed("user")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "list all users")
	public List<UserDto> getAll() {
		return User.findAll().<User>stream().map(UserDto::justPublicInfo).toList();
	}

}