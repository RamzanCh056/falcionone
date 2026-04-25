import 'package:falcon_one_demo/controllers/glasses_controller.dart';
import 'package:falcon_one_demo/controllers/map_controller.dart';
import 'package:falcon_one_demo/services/glasses_service.dart';
import 'package:falcon_one_demo/services/upload_service.dart';
import 'package:get/get.dart';

class MapBinding extends Bindings {
  @override
  void dependencies() {
    Get.lazyPut<UploadService>(() => UploadService(officerCode: MapController.officerCode));
    Get.lazyPut<GlassesService>(() => GlassesService(), fenix: true);
    Get.lazyPut<GlassesController>(() => GlassesController(), fenix: true);
    Get.lazyPut<MapController>(() => MapController());
  }
}
