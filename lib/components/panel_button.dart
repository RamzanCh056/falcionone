import 'package:flutter/material.dart';

class PanelButton extends StatelessWidget {
  const PanelButton({super.key, required this.iconData, this.onTap, this.onLongPress});

  final IconData iconData;
  final void Function()? onTap;
  final void Function()? onLongPress;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Center(
        child: Ink(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20.0),
            color: Colors.grey.withAlpha(30),
          ),
          child: InkWell(
            borderRadius: BorderRadius.circular(20.0),
            onTap: onTap,
            onLongPress: onLongPress,
            child: Center(
              child: Icon(iconData, size: 40.0, color: Colors.white),
            ),
          ),
        ),
      ),
    );
  }
}
