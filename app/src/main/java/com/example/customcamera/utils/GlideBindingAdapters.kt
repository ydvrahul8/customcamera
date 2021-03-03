package com.example.customcamera.utils

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.customcamera.R

@BindingAdapter("imageResource")
fun setImageResource(view: ImageView, imageUrl: String?) {
    val context = view.context
    val option = RequestOptions()
        .placeholder(R.drawable.ic_launcher_background)
        .error(R.drawable.ic_launcher_background)
    Glide.with(context)
        .setDefaultRequestOptions(option)
        .load(BitmapFactory.decodeFile(imageUrl))
        .into(view)
}
