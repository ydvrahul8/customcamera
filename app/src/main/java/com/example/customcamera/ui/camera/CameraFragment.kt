package com.example.customcamera.ui.camera

import android.graphics.*
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.TextureViewMeteringPointFactory
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.Navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.example.customcamera.R
import com.example.customcamera.utils.BitmapUtils
import com.example.customcamera.utils.FileCreator
import com.example.customcamera.utils.FileCreator.JPEG_FORMAT
import com.example.customcamera.utils.PathParser
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.fragment_camera.*
import java.io.*
import java.util.concurrent.Executors

/**
 * A simple [Fragment] subclass.
 */
class CameraFragment : Fragment() {

    private lateinit var navController: NavController
    private lateinit var processCameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var processCameraProvider: ProcessCameraProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processCameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navController = Navigation.findNavController(view)
        processCameraProviderFuture.addListener(Runnable {
            processCameraProvider = processCameraProviderFuture.get()
            viewFinder.post { setupCamera() }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::processCameraProvider.isInitialized) {
            processCameraProvider.unbindAll()
        }
    }

    private fun setupCamera() {
        processCameraProvider.unbindAll()
        val camera = processCameraProvider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            buildPreviewUseCase(),
            buildImageCaptureUseCase(),
            buildImageAnalysisUseCase())
        setupTapForFocus(camera.cameraControl)
    }

    private fun buildPreviewUseCase(): Preview {
        val display = viewFinder.display
        val metrics = DisplayMetrics().also { display.getMetrics(it) }
        val preview = Preview.Builder()
            .setTargetRotation(display.rotation)
            .setTargetResolution(Size(metrics.widthPixels, metrics.heightPixels))
            .build()
            .apply {
                previewSurfaceProvider = viewFinder.previewSurfaceProvider
            }
        preview.previewSurfaceProvider = viewFinder.previewSurfaceProvider
        return preview
    }

    private fun buildImageCaptureUseCase(): ImageCapture {
        val display = viewFinder.display
        val metrics = DisplayMetrics().also { display.getMetrics(it) }
        val capture = ImageCapture.Builder()
            .setTargetRotation(display.rotation)
            .setTargetResolution(Size(metrics.widthPixels, metrics.heightPixels))
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val executor = Executors.newSingleThreadExecutor()
        cameraCaptureImageButton.setOnClickListener {
            capture.takePicture(
                FileCreator.createTempFile(JPEG_FORMAT),
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(file: File) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        val rotatedBitmap = bitmap.rotate(90)
                        val croppedImage = convertShape(rotatedBitmap)
                        val path = saveImage(croppedImage)
                        requireActivity().runOnUiThread {
                            launchGalleryFragment(path)
                        }
                    }

                    override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) {
                        Toast.makeText(requireContext(), "Error: $message", Toast.LENGTH_LONG).show()
                        Log.e("CameraFragment", "Capture error $imageCaptureError: $message", cause)
                    }
                })
        }
        return capture
    }

    private fun buildImageAnalysisUseCase(): ImageAnalysis {
        val display = viewFinder.display
        val metrics = DisplayMetrics().also { display.getMetrics(it) }
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(display.rotation)
            .setTargetResolution(Size(metrics.widthPixels, metrics.heightPixels))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
            .setImageQueueDepth(10)
            .build()
        analysis.setAnalyzer(
            Executors.newSingleThreadExecutor(),
            ImageAnalysis.Analyzer { imageProxy ->
                Log.d("CameraFragment", "Image analysis result $imageProxy")
                imageProxy.close()
            })
        return analysis
    }

    private fun setupTapForFocus(cameraControl: CameraControl) {
        viewFinder.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP) {
                return@setOnTouchListener true
            }

            val textureView = viewFinder.getChildAt(0) as? TextureView
                ?: return@setOnTouchListener true
            val factory = TextureViewMeteringPointFactory(textureView)

            val point = factory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder.from(point).build()
            cameraControl.startFocusAndMetering(action)
            return@setOnTouchListener true
        }
    }


    fun convertShape(src: Bitmap): ByteArray {
        val bitmapFinal = BitmapUtils.getCroppedBitmap(src, getShapePath(src))
        val stream = ByteArrayOutputStream()
        bitmapFinal.compress(
            Bitmap.CompressFormat.JPEG,
            100,
            stream
        ) //100 is the best quality possible
        return stream.toByteArray()
    }

    private fun getShapePath(src: Bitmap): Path? {
        return resizePath(
            PathParser.createPathFromPathData(getString(R.string.oval)),
            src.width.toFloat(), src.height.toFloat()
        )
    }


    fun resizePath(
        path: Path?,
        width: Float,
        height: Float
    ): Path? {
        val bounds = RectF(0f, 0f, width, height)
        val resizedPath = Path(path)
        val src = RectF()
        resizedPath.computeBounds(src, true)
        val resizeMatrix = Matrix()
        resizeMatrix.setRectToRect(src, bounds, Matrix.ScaleToFit.CENTER)
        resizedPath.transform(resizeMatrix)
        return resizedPath
    }

    // Save the image cropped
    private fun saveImage(bytes: ByteArray) : String {
        val outStream: FileOutputStream
        val fileName = "KTP" + System.currentTimeMillis() + ".jpg"
        val directoryName = activity?.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(directoryName, fileName)
        Log.e("Camera fragment", "saveImage: "+file.absolutePath )
        if (!directoryName?.exists()!!) {
            directoryName.mkdirs()
        }

        try {
            file.createNewFile()
            outStream = FileOutputStream(file)
            outStream.write(bytes)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.path),
                arrayOf("image/jpeg"), null
            )
            outStream.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return file.absolutePath
    }


    // Rotate the image from Landscape to Portrait
    private fun Bitmap.rotate(degree:Int):Bitmap{
        // Initialize a new matrix
        val matrix = Matrix()

        // Rotate the bitmap
        matrix.postRotate(degree.toFloat())

        // Resize the bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(
            this,
            width,
            height,
            true
        )

        // Create and return the rotated bitmap
        return Bitmap.createBitmap(
            scaledBitmap,
            0,
            0,
            scaledBitmap.width,
            scaledBitmap.height,
            matrix,
            true
        )
    }

    private fun launchGalleryFragment(path: String) {

        val bundle = bundleOf("data" to path)
        navController.navigate(R.id.actionLaunchGalleryFragment,bundle)
    }

}
