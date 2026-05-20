package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;

public final class NpcSensorTypes {

    private NpcSensorTypes() {}

    public static final DeferredRegister<SensorType<?>> SENSORS =
            DeferredRegister.create(BuiltInRegistries.SENSOR_TYPE,
                    Life_in_the_village.MODID);

    public static final DeferredHolder<SensorType<?>, SensorType<MoodSnapshotSensor>>
            MOOD_SNAPSHOT = reg("mood_snapshot", MoodSnapshotSensor::new);

    public static final DeferredHolder<SensorType<?>, SensorType<HomeAndJobSiteSensor>>
            HOME_AND_JOB_SITE = reg("home_and_job_site", HomeAndJobSiteSensor::new);

    public static final DeferredHolder<SensorType<?>, SensorType<ConversationCandidatesSensor>>
            CONVERSATION_CANDIDATES = reg("conversation_candidates", ConversationCandidatesSensor::new);

    public static final DeferredHolder<SensorType<?>, SensorType<NearbyFreeSeatsSensor>>
            NEARBY_FREE_SEATS = reg("nearby_free_seats", NearbyFreeSeatsSensor::new);

    public static final DeferredHolder<SensorType<?>, SensorType<WeatherShelterSensor>>
            WEATHER_SHELTER = reg("weather_shelter", WeatherShelterSensor::new);

    public static final DeferredHolder<SensorType<?>, SensorType<EscortRelationshipSensor>>
            ESCORT_RELATIONSHIP = reg("escort_relationship", EscortRelationshipSensor::new);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <S extends Sensor<TownspersonMob>>
            DeferredHolder<SensorType<?>, SensorType<S>> reg(
                    String name, java.util.function.Supplier<S> ctor) {
        return (DeferredHolder) SENSORS.register(name,
                () -> new SensorType<S>(ctor));
    }

    public static void register(IEventBus bus) {
        SENSORS.register(bus);
    }
}
