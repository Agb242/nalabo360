package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.util.KLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

private const val TAG = "IosCameraController"

/**
 * The AVFoundation half of iOS capture: a photo-preset session bound to the
 * back wide camera, a preview layer the Compose host embeds, and one-shot
 * captures handed back as raw JPEG bytes.
 *
 * Session work runs on a private serial queue — Apple's contract is that
 * configuration and start/stop never touch the main thread while the session is
 * live. The shutter may be pulled from composition code; it hops queues itself.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCameraController {

    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private val cameraQueue = dispatch_queue_create("com.n30dyn4m1c.nalabo360.camera", null)

    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var input: AVCaptureDeviceInput? = null

    /** Keeps the delegate alive until its callback fires; ARC would drop it otherwise. */
    private var activeDelegate: PhotoCaptureDelegate? = null

    private var configured = false

    /** True once the session reports itself running. Read from composition. */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Builds the view the viewfinder draws into. Called once by UIKitView's
     * factory; sizing follows in [layoutPreview].
     */
    fun createPreviewView(): UIView {
        val view = UIView()
        val layer = AVCaptureVideoPreviewLayer.layerWithSession(session)
        // The constant lives in a framework fragment the Kotlin bindings do not
        // surface; the literal string is its stable public value.
        layer.videoGravity = "avLayerVideoGravityResizeAspectFill"
        view.layer.addSublayer(layer)
        previewLayer = layer
        return view
    }

    /** Keeps the preview layer stretched over its host view. */
    fun layoutPreview(view: UIView) {
        previewLayer?.frame = view.bounds
    }

    /** Configures and starts the session, off-thread. Idempotent. */
    fun start() {
        dispatch_async(cameraQueue) {
            if (configured) return@dispatch_async
            configured = true

            try {
                session.beginConfiguration()
                session.sessionPreset = AVCaptureSessionPresetPhoto

                // The default video device is the rear wide camera on every
                // iPhone Apple ships — the lens this app wants anyway.
                val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
                val deviceInput = device?.let { camera ->
                    memScoped {
                        val bindError = alloc<ObjCObjectVar<NSError?>>()
                        // A nil Obj-C initializer surfaces as an exception here;
                        // either way the null-check below reports it.
                        runCatching { AVCaptureDeviceInput(camera, bindError.ptr) }.getOrNull()
                    }
                }
                if (deviceInput != null && session.canAddInput(deviceInput)) {
                    session.addInput(deviceInput)
                    input = deviceInput
                } else {
                    KLog.e(TAG, "No back camera available to bind")
                }

                if (session.canAddOutput(photoOutput)) {
                    session.addOutput(photoOutput)
                } else {
                    KLog.e(TAG, "Photo output refused")
                }
                session.commitConfiguration()
                session.startRunning()
                isRunning = session.isRunning()
            } catch (error: Exception) {
                configured = false
                KLog.e(TAG, "Could not start the capture session", error)
            }
        }
    }

    /** Stops the session and releases the camera for other apps. Idempotent. */
    fun stop() {
        dispatch_async(cameraQueue) {
            if (session.isRunning()) session.stopRunning()
            isRunning = false
            configured = false
        }
    }

    /**
     * Fires the shutter; [onCaptured] receives the JPEG bytes, or null when the
     * capture failed. Safe to call from any thread.
     */
    fun capture(onCaptured: (NSData?) -> Unit) {
        if (!isRunning) {
            onCaptured(null)
            return
        }
        val delegate = PhotoCaptureDelegate { data ->
            activeDelegate = null
            dispatch_async(dispatch_get_main_queue()) { onCaptured(data) }
        }
        activeDelegate = delegate
        photoOutput.capturePhotoWithSettings(
            /* settings = */ AVCapturePhotoSettings(),
            /* delegate = */ delegate,
        )
    }
}

/** Bridges AVFoundation's callback into a single nullable-data result. */
private class PhotoCaptureDelegate(
    private val onResult: (NSData?) -> Unit,
) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {

    override fun captureOutput(
        captureOutput: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            KLog.w(TAG, "Capture failed: ${error.localizedDescription}")
        }
        onResult(didFinishProcessingPhoto.fileDataRepresentation())
    }
}
