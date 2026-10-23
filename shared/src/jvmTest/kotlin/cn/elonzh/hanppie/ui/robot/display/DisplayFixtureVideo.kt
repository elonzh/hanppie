package cn.elonzh.hanppie.ui.robot.display

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Clearly labelled synthetic picture with off-centre landmarks for Fit/Crop and calibration QA. */
internal fun displayFixtureVideo(): ImageBitmap {
    val image=BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB)
    val graphics=image.createGraphics()
    graphics.color=Color(58,68,76);graphics.fillRect(0,0,1280,720)
    graphics.color=Color(79,89,97);graphics.fillRect(0,430,1280,290)
    graphics.color=Color(122,145,155);graphics.fillRect(80,140,170,290)
    graphics.color=Color(180,151,102);graphics.fillRect(1010,280,170,220)
    graphics.color=Color(223,107,56);graphics.fillOval(622,342,36,36)
    graphics.color=Color.WHITE;graphics.font=Font(Font.SANS_SERIF,Font.BOLD,24)
    graphics.drawString("SYNTHETIC VIDEO / OFFLINE",360,100)
    graphics.font=Font(Font.SANS_SERIF,Font.PLAIN,20)
    graphics.drawString("REFERENCE",580,415)
    graphics.dispose()
    val output=ByteArrayOutputStream();ImageIO.write(image,"png",output)
    return org.jetbrains.skia.Image.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
}
